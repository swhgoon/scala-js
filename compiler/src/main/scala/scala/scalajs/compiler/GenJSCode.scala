/* Scala.js compiler
 * Copyright 2013 LAMP/EPFL
 * @author  Sébastien Doeraene
 */

package scala.scalajs.compiler

import scala.language.implicitConversions

import scala.annotation.switch

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import scala.tools.nsc._

import scala.annotation.tailrec

import scala.scalajs.ir
import ir.{Trees => js, Types => jstpe, ClassKind}

import util.ScopedVar
import ScopedVar.withScopedVars

/** Generate JavaScript code and output it to disk
 *
 *  @author Sébastien Doeraene
 */
abstract class GenJSCode extends plugins.PluginComponent
                            with TypeKinds
                            with JSEncoding
                            with GenJSExports
                            with ClassInfos
                            with GenJSFiles
                            with Compat210Component {

  val jsAddons: JSGlobalAddons {
    val global: GenJSCode.this.global.type
  }

  val scalaJSOpts: ScalaJSOptions

  import global._
  import jsAddons._
  import rootMirror._
  import definitions._
  import jsDefinitions._
  import JSTreeExtractors._

  import treeInfo.hasSynthCaseSymbol

  import platform.isMaybeBoxed

  val phaseName = "jscode"

  /** testing: this will be called when ASTs are generated */
  def generatedJSAST(clDefs: List[js.Tree]): Unit

  /** Implicit conversion from nsc Position to ir.Position. */
  implicit def pos2irPos(pos: Position): ir.Position = {
    if (pos == NoPosition) ir.Position.NoPosition
    else {
      val source = pos2irPosCache.toIRSource(pos.source)
      // nsc positions are 1-based but IR positions are 0-based
      ir.Position(source, pos.line-1, pos.column-1)
    }
  }

  private[this] object pos2irPosCache {
    import scala.reflect.internal.util._

    private[this] var lastNscSource: SourceFile = null
    private[this] var lastIRSource: ir.Position.SourceFile = null

    def toIRSource(nscSource: SourceFile): ir.Position.SourceFile = {
      if (nscSource != lastNscSource) {
        lastIRSource = convert(nscSource)
        lastNscSource = nscSource
      }
      lastIRSource
    }

    private[this] def convert(nscSource: SourceFile): ir.Position.SourceFile = {
      nscSource.file.file match {
        case null =>
          new java.net.URI(
              "virtualfile",       // Pseudo-Scheme
              nscSource.file.path, // Scheme specific part
              null                 // Fragment
          )
        case file =>
          val relURI = scalaJSOpts.relSourceMap.fold(file.toURI)(_.relativize(file.toURI))
          val absURI = scalaJSOpts.absSourceMap.fold(relURI)(_.resolve(relURI))
          absURI
      }
    }

    def clear(): Unit = {
      lastNscSource = null
      lastIRSource = null
    }
  }

  /** Materialize implicitly an ir.Position from an implicit nsc Position. */
  implicit def implicitPos2irPos(implicit pos: Position): ir.Position = pos

  override def newPhase(p: Phase) = new JSCodePhase(p)

  class JSCodePhase(prev: Phase) extends StdPhase(prev) with JSExportsPhase {

    override def name = phaseName
    override def description = "Generate JavaScript code from ASTs"
    override def erasedTypes = true

    // Some state --------------------------------------------------------------

    val currentClassSym          = new ScopedVar[Symbol]
    val currentClassInfoBuilder  = new ScopedVar[ClassInfoBuilder]
    val currentMethodSym         = new ScopedVar[Symbol]
    val currentMethodInfoBuilder = new ScopedVar[MethodInfoBuilder]
    val methodTailJumpThisSym    = new ScopedVar[Symbol]
    val methodTailJumpLabelSym   = new ScopedVar[Symbol]
    val methodTailJumpFormalArgs = new ScopedVar[List[Symbol]]
    val paramAccessorLocals      = new ScopedVar(Map.empty[Symbol, js.ParamDef])

    var isModuleInitialized: Boolean = false // see genApply for super calls

    def currentClassType = encodeClassType(currentClassSym)

    // Fresh local name generator ----------------------------------------------

    val usedLocalNames = mutable.Set.empty[String]
    val localSymbolNames = mutable.Map.empty[Symbol, String]
    private val isKeywordOrReserved =
      js.isKeyword ++ Seq("arguments", ScalaJSEnvironmentName)

    def freshName(base: String = "x"): String = {
      var suffix = 1
      var longName = base
      while (usedLocalNames(longName) || isKeywordOrReserved(longName)) {
        suffix += 1
        longName = base+"$"+suffix
      }
      usedLocalNames += longName
      longName
    }

    def freshName(sym: Symbol): String =
      localSymbolNames.getOrElseUpdate(sym, freshName(sym.name.toString))

    // Rewriting of anonymous function classes ---------------------------------

    private val translatedAnonFunctions =
      mutable.Map.empty[Symbol,
        (/*ctor args:*/ List[js.Tree] => /*instance:*/ js.Tree, ClassInfoBuilder)]
    private val instantiatedAnonFunctions =
      mutable.Set.empty[Symbol]
    private val undefinedDefaultParams =
      mutable.Set.empty[Symbol]

    // Top-level apply ---------------------------------------------------------

    override def run() {
      scalaPrimitives.init()
      jsPrimitives.init()
      super.run()
    }

    /** Generate JS code for a compilation unit
     *  This method iterates over all the class and interface definitions
     *  found in the compilation unit and emits their code (.js) and type
     *  definitions (.jstype).
     *
     *  Classes representing primitive types, as well as the scala.Array
     *  class, are not actually emitted.
     *
     *  Other ClassDefs are emitted according to their nature:
     *  * Interface               -> `genInterface()`
     *  * Implementation class    -> `genImplClass()`
     *  * Raw JS type (<: js.Any) -> `genRawJSClassData()`
     *  * Normal class            -> `genClass()`
     *                               + `genModuleAccessor()` if module class
     *
     *  The resulting tree is desugared with `JSDesugaring`, and then sent to
     *  disc with `GenJSFiles`.
     *
     *  Type definitions (i.e., pickles) for top-level representatives are also
     *  emitted.
     */
    override def apply(cunit: CompilationUnit) {
      try {
        val generatedClasses = ListBuffer.empty[(Symbol, js.Tree, ClassInfoBuilder)]

        def collectClassDefs(tree: Tree): List[ClassDef] = {
          tree match {
            case EmptyTree => Nil
            case PackageDef(_, stats) => stats flatMap collectClassDefs
            case cd: ClassDef => cd :: Nil
          }
        }
        val allClassDefs = collectClassDefs(cunit.body)

        /* First gen and record lambdas for js.FunctionN and js.ThisFunctionN.
         * Since they are SAMs, there cannot be dependencies within this set,
         * and hence we are sure we can record them before they are used,
         * which is critical for these.
         */
        val nonRawJSFunctionDefs = allClassDefs filterNot { cd =>
          if (isRawJSFunctionDef(cd.symbol)) {
            genAndRecordRawJSFunctionClass(cd)
            true
          } else {
            false
          }
        }

        /* Then try to gen and record lambdas for scala.FunctionN.
         * These may fail, and sometimes because of dependencies. Since there
         * appears to be more forward dependencies than backward dependencies
         * (at least for non-nested lambdas, which we cannot translate anyway),
         * we process class defs in reverse order here.
         */
        val fullClassDefs = (nonRawJSFunctionDefs.reverse filterNot { cd =>
          cd.symbol.isAnonymousFunction && tryGenAndRecordAnonFunctionClass(cd)
        }).reverse

        /* Finally, we emit true code for the remaining class defs. */
        for (cd <- fullClassDefs) {
          val sym = cd.symbol
          implicit val pos = sym.pos

          /* Do not actually emit code for primitive types nor scala.Array. */
          val isPrimitive =
            isPrimitiveValueClass(sym) || (sym == ArrayClass)

          /* Similarly, do not emit code for impl classes of raw JS traits. */
          val isRawJSImplClass =
            sym.isImplClass && isRawJSType(
                sym.owner.info.decl(sym.name.dropRight(nme.IMPL_CLASS_SUFFIX.length)).tpe)

          if (!isPrimitive && !isRawJSImplClass) {
            withScopedVars(
                currentClassInfoBuilder := new ClassInfoBuilder(sym.asClass),
                currentClassSym         := sym
            ) {
              val tree = if (isRawJSType(sym.tpe)) {
                assert(!isRawJSFunctionDef(sym),
                    s"Raw JS function def should have been recorded: $cd")
                genRawJSClassData(cd)
              } else if (sym.isInterface) {
                genInterface(cd)
              } else if (sym.isImplClass) {
                genImplClass(cd)
              } else if (isHijackedBoxedClass(sym)) {
                genHijackedBoxedClassData(cd)
              } else {
                genClass(cd)
              }
              generatedClasses += ((sym, tree, currentClassInfoBuilder.get))
            }
          }
        }

        val clDefs = generatedClasses.map(_._2).toList
        generatedJSAST(clDefs)

        for ((sym, tree, infoBuilder) <- generatedClasses) {
          genIRFile(cunit, sym, tree, infoBuilder.result())
        }
      } finally {
        translatedAnonFunctions.clear()
        instantiatedAnonFunctions.clear()
        undefinedDefaultParams.clear()
        pos2irPosCache.clear()
      }
    }

    // Generate a class --------------------------------------------------------

    /** Gen JS code for a class definition (maybe a module class)
     *  It emits:
     *  * An ES6 class declaration with:
     *    - A constructor creating all the fields (ValDefs)
     *    - Methods (DefDefs), including the Scala constructor
     *    - JS-friendly bridges for all public methods (with non-mangled names)
     *  * An inheritable constructor, used to create the prototype of subclasses
     *  * A JS-friendly constructor bridge, if there is a public constructor
     *  * Functions for instance tests
     *  * The class data record
     */
    def genClass(cd: ClassDef): js.Tree = {
      val ClassDef(mods, name, _, impl) = cd
      val sym = cd.symbol
      implicit val pos = sym.pos

      assert(!sym.isInterface && !sym.isImplClass,
          "genClass() must be called only for normal classes: "+sym)
      assert(sym.superClass != NoSymbol, sym)

      val classIdent = encodeClassFullNameIdent(sym)

      // Generate members (constructor + methods)

      val generatedMembers = new ListBuffer[js.Tree]
      val exportedSymbols = new ListBuffer[Symbol]

      generatedMembers ++= genClassFields(cd)

      def gen(tree: Tree): Unit = {
        tree match {
          case EmptyTree => ()
          case Template(_, _, body) => body foreach gen

          case ValDef(mods, name, tpt, rhs) =>
            () // fields are added via genClassFields()

          case dd: DefDef =>
            val sym = dd.symbol
            generatedMembers ++= genMethod(dd)

            if (jsInterop.isExport(sym)) {
              // We add symbols that we have to export here. This way we also
              // get inherited stuff that is implemented in this class.
              exportedSymbols += sym
            }

          case _ => abort("Illegal tree in gen of genClass(): " + tree)
        }
      }

      gen(impl)

      // Create method info builder for exported stuff
      val exports = withScopedVars(
        currentMethodInfoBuilder := currentClassInfoBuilder.addMethod(
            dceExportName + classIdent.name, isExported = true)
      ) {
        // Generate the exported members
        val memberExports = genMemberExports(sym, exportedSymbols.toList)

        // Generate exported constructors or accessors
        val exportedConstructorsOrAccessors =
          if (isStaticModule(sym)) genModuleAccessorExports(sym)
          else genConstructorExports(sym)
        if (exportedConstructorsOrAccessors.nonEmpty)
          currentClassInfoBuilder.isExported = true

        memberExports ++ exportedConstructorsOrAccessors
      }

      // Generate the reflective call proxies (where required)
      val reflProxies = genReflCallProxies(sym)

      // The complete class definition
      val classDefinition = js.ClassDef(
          classIdent,
          if (sym.isModuleClass) ClassKind.ModuleClass else ClassKind.Class,
          Some(encodeClassFullNameIdent(sym.superClass)),
          sym.ancestors.map(encodeClassFullNameIdent),
          generatedMembers.toList ++ exports ++ reflProxies)

      classDefinition
    }

    // Generate the class data of a raw JS class -------------------------------

    /** Gen JS code creating the class data of a raw JS class
     */
    def genRawJSClassData(cd: ClassDef): js.Tree = {
      val sym = cd.symbol
      implicit val pos = sym.pos

      // Check that RawJS type is not exported
      for ( (_, pos) <- jsInterop.exportsOf(sym) ) {
        currentUnit.error(pos, "You may not export a class extending js.Any")
      }

      val classIdent = encodeClassFullNameIdent(sym)
      js.ClassDef(classIdent, ClassKind.RawJSType, None, Nil, Nil)
    }

    // Generate the class data of a hijacked boxed class -----------------------

    /** Gen JS code creating the class data of a hijacked boxed class
     */
    def genHijackedBoxedClassData(cd: ClassDef): js.Tree = {
      val sym = cd.symbol
      implicit val pos = sym.pos
      val classIdent = encodeClassFullNameIdent(sym)
      js.ClassDef(classIdent, ClassKind.HijackedClass, None, Nil, Nil)
    }

    // Generate an interface ---------------------------------------------------

    /** Gen JS code for an interface definition
     *  This is very simple, as interfaces have virtually no existence at
     *  runtime. They exist solely for reflection purposes.
     */
    def genInterface(cd: ClassDef): js.Tree = {
      val sym = cd.symbol
      implicit val pos = sym.pos

      val classIdent = encodeClassFullNameIdent(sym)

      // fill in class info builder
      def gen(tree: Tree) {
        tree match {
          case EmptyTree => ()
          case Template(_, _, body) => body foreach gen
          case dd: DefDef =>
            currentClassInfoBuilder.addMethod(
                encodeMethodName(dd.symbol), isAbstract = true)
          case _ => abort("Illegal tree in gen of genInterface(): " + tree)
        }
      }
      gen(cd.impl)

      // Check that interface/trait is not exported
      for ( (_, pos) <- jsInterop.exportsOf(sym) ) {
        currentUnit.error(pos, "You may not export a trait")
      }

      js.ClassDef(classIdent, ClassKind.Interface, None,
          sym.ancestors.map(encodeClassFullNameIdent), Nil)
    }

    // Generate an implementation class of a trait -----------------------------

    /** Gen JS code for an implementation class (of a trait)
     */
    def genImplClass(cd: ClassDef): js.Tree = {
      val ClassDef(mods, name, _, impl) = cd
      val sym = cd.symbol
      implicit val pos = sym.pos

      def gen(tree: Tree): List[js.MethodDef] = {
        tree match {
          case EmptyTree => Nil
          case Template(_, _, body) => body.flatMap(gen)

          case dd: DefDef =>
            val m = genMethod(dd)
            m.toList

          case _ => abort("Illegal tree in gen of genImplClass(): " + tree)
        }
      }
      val generatedMethods = gen(impl)

      js.ClassDef(encodeClassFullNameIdent(sym), ClassKind.TraitImpl,
          None, Nil, generatedMethods)
    }

    // Generate the fields of a class ------------------------------------------

    /** Gen definitions for the fields of a class.
     *  The fields are initialized with the zero of their types.
     */
    def genClassFields(cd: ClassDef): List[js.VarDef] = withScopedVars(
        currentMethodInfoBuilder :=
          currentClassInfoBuilder.addMethod("__init__")
    ) {
      // Non-method term members are fields, except for module members.
      (for {
        f <- currentClassSym.info.decls
        if !f.isMethod && f.isTerm && !f.isModule
      } yield {
        implicit val pos = f.pos
        js.VarDef(encodeFieldSym(f), toTypeKind(f.tpe).toIRType,
            mutable = f.isMutable, genZeroOf(f.tpe))
      }).toList
    }

    // Generate a method -------------------------------------------------------

    def genMethod(dd: DefDef): Option[js.MethodDef] =
      genMethodWithInfoBuilder(dd).map(_._1)

    /** Gen JS code for a method definition in a class.
     *  On the JS side, method names are mangled to encode the full signature
     *  of the Scala method, as described in `JSEncoding`, to support
     *  overloading.
     *
     *  Some methods are not emitted at all:
     *  * Primitives, since they are never actually called
     *  * Abstract methods (alternative: throw a java.lang.AbstractMethodError)
     *  * Trivial constructors, which only call their super constructor, with
     *    the same signature, and the same arguments. The JVM needs these
     *    constructors, but not JavaScript. Since there are lots of them, we
     *    take the trouble of recognizing and removing them.
     *
     *  Constructors are emitted by generating their body as a statement, then
     *  return `this`.
     *
     *  Other (normal) methods are emitted with `genMethodBody()`.
     */
    def genMethodWithInfoBuilder(
        dd: DefDef): Option[(js.MethodDef, MethodInfoBuilder)] = {

      implicit val pos = dd.pos
      val DefDef(mods, name, _, vparamss, _, rhs) = dd
      val sym = dd.symbol

      isModuleInitialized = false

      val result = withScopedVars(
          currentMethodSym         := sym,
          methodTailJumpThisSym    := NoSymbol,
          methodTailJumpLabelSym   := NoSymbol,
          methodTailJumpFormalArgs := Nil
      ) {

        /* Do NOT clear usedLocalNames and localSymbolNames!
         * genAndRecordAnonFunctionClass() starts populating them before
         * genMethod() starts.
         */

        assert(vparamss.isEmpty || vparamss.tail.isEmpty,
            "Malformed parameter list: " + vparamss)
        val params = if (vparamss.isEmpty) Nil else vparamss.head map (_.symbol)

        assert(!sym.owner.isInterface,
            "genMethod() must not be called for methods in interfaces: "+sym)

        val methodIdent = encodeMethodSym(sym)

        def createInfoBuilder(isAbstract: Boolean = false) = {
          currentClassInfoBuilder.addMethod(methodIdent.name,
              isAbstract = isAbstract,
              isExported = sym.isClassConstructor &&
                jsInterop.exportsOf(sym).nonEmpty)
        }

        if (scalaPrimitives.isPrimitive(sym)) {
          None
        } else if (sym.isDeferred) {
          createInfoBuilder(isAbstract = true)
          None
        } else if (isTrivialConstructor(sym, params, rhs)) {
          createInfoBuilder().callsMethod(sym.owner.superClass, methodIdent)
          None
        } else {
          withScopedVars(
              currentMethodInfoBuilder := createInfoBuilder()
          ) {
            currentMethodInfoBuilder.optimizerHints =
              currentMethodInfoBuilder.optimizerHints.copy(
                  isAccessor = sym.isAccessor,
                  hasInlineAnnot = sym.hasAnnotation(InlineAnnotationClass))

            val jsParams = for (param <- params) yield {
              implicit val pos = param.pos
              js.ParamDef(encodeLocalSym(param, freshName),
                  toTypeKind(param.tpe).toIRType)
            }

            val (resultType, body) = {
              if (sym.isClassConstructor) {
                (currentClassType, js.Block(
                    genStat(rhs), js.Return(js.This()(currentClassType))))
              } else {
                val resultKind = toTypeKind(sym.tpe.resultType)
                (resultKind.toIRType, genMethodBody(rhs, params, resultKind))
              }
            }

            val methodDef =
              js.MethodDef(methodIdent, jsParams, resultType, body)

            Some((methodDef, currentMethodInfoBuilder.get))
          }
        }
      }

      usedLocalNames.clear()
      localSymbolNames.clear()

      result
    }

    private def isTrivialConstructor(sym: Symbol, params: List[Symbol],
        rhs: Tree): Boolean = {
      if (!sym.isClassConstructor) {
        false
      } else {
        rhs match {
          // Shape of a constructor that only calls super
          case Block(List(Apply(fun @ Select(_:Super, _), args)), Literal(_)) =>
            val callee = fun.symbol
            implicit val dummyPos = NoPosition

            // Does the callee have the same signature as sym
            if (encodeMethodSym(sym) == encodeMethodSym(callee)) {
              // Test whether args are trivial forwarders
              assert(args.size == params.size, "Argument count mismatch")
              params.zip(args) forall { case (param, arg) =>
                arg.symbol == param
              }
            } else {
              false
            }

          case _ => false
        }
      }
    }

    /**
     * Generates reflective proxy methods for methods in sym
     *
     * Reflective calls don't depend on the return type, so it's hard to
     * generate calls without using runtime reflection to list the methods. We
     * generate a method to be used for reflective calls (without return
     * type in the name).
     *
     * There are cases where non-trivial overloads cause ambiguous situations:
     *
     * {{{
     * object A {
     *   def foo(x: Option[Int]): String
     *   def foo(x: Option[String]): Int
     * }
     * }}}
     *
     * This is completely legal code, but due to the same erased parameter
     * type of the {{{foo}}} overloads, they cannot be disambiguated in a
     * reflective call, as the exact return type is unknown at the call site.
     *
     * Cases like the upper currently fail on the JVM backend at runtime. The
     * Scala.js backend uses the following rules for selection (which will
     * also cause runtime failures):
     *
     * - If a proxy with the same signature (method name and parameters)
     *   exists in the superclass, no proxy is generated (proxy is inherited)
     * - If no proxy exists in the superclass, a proxy is generated for the
     *   first method with matching signatures.
     */
    def genReflCallProxies(sym: Symbol): List[js.Tree] = {
      import scala.reflect.internal.Flags

      // Flags of members we do not want to consider for reflective call proxys
      val excludedFlags = (
          Flags.BRIDGE  |
          Flags.PRIVATE |
          Flags.MACRO
      )

      /** Check if two method symbols conform in name and parameter types */
      def weakMatch(s1: Symbol)(s2: Symbol) = {
        val p1 = s1.tpe.params
        val p2 = s2.tpe.params
        s1 == s2 || // Shortcut
        s1.name == s2.name &&
        p1.size == p2.size &&
        (p1 zip p2).forall { case (s1,s2) =>
          s1.tpe =:= s2.tpe
        }
      }

      /** Check if the symbol's owner's superclass has a matching member (and
       *  therefore an existing proxy).
       */
      def superHasProxy(s: Symbol) = {
        val alts = sym.superClass.tpe.findMember(
            name = s.name,
            excludedFlags = excludedFlags,
            requiredFlags = Flags.METHOD,
            stableOnly    = false).alternatives
        alts.exists(weakMatch(s) _)
      }

      // Query candidate methods
      val methods = sym.tpe.findMembers(
          excludedFlags = excludedFlags,
          requiredFlags = Flags.METHOD)

      val candidates = methods filterNot { s =>
        s.isConstructor  ||
        superHasProxy(s) ||
        jsInterop.isExport(s)
      }

      val proxies = candidates filter {
        c => candidates.find(weakMatch(c) _).get == c
      }

      proxies.map(genReflCallProxy _).toList
    }

    /** actually generates reflective call proxy for the given method symbol */
    private def genReflCallProxy(sym: Symbol): js.Tree = {
      implicit val pos = sym.pos

      val proxyIdent = encodeMethodSym(sym, reflProxy = true)

      withScopedVars(
          currentMethodInfoBuilder :=
            currentClassInfoBuilder.addMethod(proxyIdent.name)
      ) {
        val jsParams = for (param <- sym.tpe.params) yield {
          implicit val pos = param.pos
          val name = encodeLocalSym(param, freshName)
          val tpe = toTypeKind(param.tpe).toIRType
          (js.ParamDef(name, tpe), js.VarRef(name, false)(tpe))
        }

        val call = genApplyMethod(js.This()(currentClassType), sym.owner, sym,
            jsParams.map(_._2))
        val value = ensureBoxed(call,
            enteringPhase(currentRun.posterasurePhase)(sym.tpe.resultType))

        val body = js.Return(value)

        js.MethodDef(proxyIdent, jsParams.map(_._1), jstpe.AnyType, body)
      }
    }

    /** Generate the body of a (non-constructor) method
     *
     *  Most normal methods are emitted straightforwardly. If the result
     *  type is Unit, then the body is emitted as a statement. Otherwise, it is
     *  emitted as an expression and wrapped in a `js.Return()` statement.
     *
     *  The additional complexity of this method handles the transformation of
     *  recursive tail calls. The `tailcalls` phase unhelpfully transforms
     *  them as one big LabelDef surrounding the body of the method, and
     *  label-Apply's for recursive tail calls.
     *  Here, we transform the outer LabelDef into a labelled `while (true)`
     *  loop. Label-Apply's to the LabelDef are turned into a `continue` of
     *  that loop. The body of the loop is a `js.Return()` of the body of the
     *  LabelDef (even if the return type is Unit), which will break out of
     *  the loop as necessary.
     */
    def genMethodBody(tree: Tree, paramsSyms: List[Symbol],
        resultTypeKind: TypeKind): js.Tree = {
      implicit val pos = tree.pos

      tree match {
        case Block(
            List(thisDef @ ValDef(_, nme.THIS, _, initialThis)),
            ld @ LabelDef(labelName, _, rhs)) =>
          // This method has tail jumps
          withScopedVars(
            (methodTailJumpLabelSym := ld.symbol) +:
            (initialThis match {
              case This(_) => Seq(
                methodTailJumpThisSym    := thisDef.symbol,
                methodTailJumpFormalArgs := thisDef.symbol :: paramsSyms)
              case Ident(_) => Seq(
                methodTailJumpThisSym    := NoSymbol,
                methodTailJumpFormalArgs := paramsSyms)
            }) :_*
          ) {
            val theLoop =
              js.While(js.BooleanLiteral(true), js.Return(genExpr(rhs)),
                  Some(js.Ident("tailCallLoop")))

            if (methodTailJumpThisSym.get == NoSymbol) {
              theLoop
            } else {
              js.Block(
                  js.VarDef(encodeLocalSym(methodTailJumpThisSym, freshName),
                      currentClassType, mutable = true,
                      js.This()(currentClassType)),
                  theLoop)
            }

          }

        case _ =>
          val bodyIsStat = resultTypeKind == UNDEFINED
          if (bodyIsStat) genStat(tree)
          else js.Return(genExpr(tree))
      }
    }

    /** Gen JS code for a tree in statement position (from JS's perspective)
     *
     *  Here we handle Assign trees directly. All other types of nodes are
     *  redirected `genExpr()`.
     */
    def genStat(tree: Tree): js.Tree = {
      implicit val pos = tree.pos

      tree match {
        /** qualifier.field = rhs */
        case Assign(lhs @ Select(qualifier, _), rhs) =>
          val sym = lhs.symbol

          val member =
            if (sym.isStaticMember) {
              genStaticMember(sym)
            } else {
              js.Select(genExpr(qualifier), encodeFieldSym(sym),
                  mutable = true)(toIRType(sym.tpe))
            }

          js.Assign(member, genExpr(rhs))

        /** lhs = rhs */
        case Assign(lhs, rhs) =>
          val sym = lhs.symbol
          js.Assign(
              js.VarRef(encodeLocalSym(sym, freshName), mutable = true)(
                  (toIRType(sym.tpe))),
              genExpr(rhs))

        case _ =>
          exprToStat(genExpr(tree))
      }
    }

    /** Turn a JavaScript statement into an expression of type Unit */
    def statToExpr(tree: js.Tree): js.Tree = {
      implicit val pos = tree.pos
      js.Block(tree, js.Undefined())
    }

    /** Turn a JavaScript expression of type Unit into a statement */
    def exprToStat(tree: js.Tree): js.Tree = {
      /* Any JavaScript expression is also a statement, but at least we get rid
       * of the stupid js.Block(..., js.Undefined()) that we create ourselves
       * in statToExpr().
       * We also remove any uV() wrapper.
       */
      implicit val pos = tree.pos
      tree match {
        case js.Block(stats :+ js.Undefined()) => js.Block(stats)
        case js.Undefined()                    => js.Skip()
        case js.CallHelper("uV", List(expr))   => exprToStat(expr)
        case _ => tree
      }
    }

    /** Gen JS code for a tree in expression position (from JS's perspective)
     *
     *  This is the main transformation method. Each node of the Scala AST
     *  is transformed into an equivalent portion of the JS AST.
     */
    def genExpr(tree: Tree): js.Tree = {
      implicit val pos = tree.pos

      /** Predicate satisfied by LabelDefs produced by the pattern matcher */
      def isCaseLabelDef(tree: Tree) =
        tree.isInstanceOf[LabelDef] && hasSynthCaseSymbol(tree)

      tree match {
        /** LabelDefs (for while and do..while loops) */
        case lblDf: LabelDef =>
          genLabelDef(lblDf)

        /** val nme.THIS = this
         *  Must have been eliminated by the tail call transform performed
         *  by `genMethodBody()`.
         */
        case ValDef(_, nme.THIS, _, _) =>
          abort("ValDef(_, nme.THIS, _, _) found at: " + tree.pos)

        /** Local val or var declaration */
        case ValDef(_, name, _, rhs) =>
          val sym = tree.symbol
          val rhsTree =
            if (rhs == EmptyTree) genZeroOf(sym.tpe)
            else genExpr(rhs)

          rhsTree match {
            case js.UndefinedParam() =>
              // This is an intermediate assignment for default params on a
              // js.Any. Add the symbol to the corresponding set to inform
              // the Ident resolver how to replace it and don't emit the symbol
              undefinedDefaultParams += sym
              statToExpr(js.Skip())
            case _ =>
              statToExpr(js.VarDef(encodeLocalSym(sym, freshName),
                  toIRType(sym.tpe), sym.isMutable, rhsTree))
          }

        case If(cond, thenp, elsep) =>
          js.If(genExpr(cond), genExpr(thenp), genExpr(elsep))(
              toIRType(tree.tpe))

        case Return(expr) =>
          js.Return(genExpr(expr))

        case t: Try =>
          genTry(t)

        case Throw(expr) =>
          val ex = genExpr(expr)
          if (isMaybeJavaScriptException(expr.tpe))
            js.Throw(js.CallHelper("unwrapJavaScriptException", ex)(jstpe.AnyType))
          else
            js.Throw(ex)

        case app: Apply =>
          genApply(app)

        case app: ApplyDynamic =>
          genApplyDynamic(app)

        /** this
         *  Normally encoded straightforwardly as a JS this.
         *  But must be replaced by the tail-jump-this local variable if there
         *  is one.
         */
        case This(qual) =>
          val symIsModuleClass = tree.symbol.isModuleClass
          assert(tree.symbol == currentClassSym.get || symIsModuleClass,
              "Trying to access the this of another class: " +
              "tree.symbol = " + tree.symbol +
              ", class symbol = " + currentClassSym.get +
              " compilation unit:" + currentUnit)
          if (symIsModuleClass && tree.symbol != currentClassSym.get) {
            genLoadModule(tree.symbol)
          } else if (methodTailJumpThisSym.get != NoSymbol) {
            js.VarRef(
              encodeLocalSym(methodTailJumpThisSym, freshName),
              mutable = true)(currentClassType)
          } else {
            js.This()(currentClassType)
          }

        case Select(Ident(nme.EMPTY_PACKAGE_NAME), module) =>
          assert(tree.symbol.isModule,
              "Selection of non-module from empty package: " + tree +
              " sym: " + tree.symbol + " at: " + (tree.pos))
          genLoadModule(tree.symbol)

        case Select(qualifier, selector) =>
          val sym = tree.symbol

          if (sym.isModule) {
            if (settings.debug.value)
              log("LOAD_MODULE from Select(qualifier, selector): " + sym)
            assert(!tree.symbol.isPackageClass, "Cannot use package as value: " + tree)
            genLoadModule(sym)
          } else if (sym.isStaticMember) {
            genStaticMember(sym)
          } else if (paramAccessorLocals contains sym) {
            paramAccessorLocals(sym).ref
          } else {
            js.Select(genExpr(qualifier), encodeFieldSym(sym),
                mutable = sym.isMutable)(toIRType(sym.tpe))
          }

        case Ident(name) =>
          val sym = tree.symbol
          if (!sym.isPackage) {
            if (sym.isModule) {
              assert(!sym.isPackageClass, "Cannot use package as value: " + tree)
              genLoadModule(sym)
            } else if (undefinedDefaultParams contains sym) {
              // This is a default parameter whose assignment was moved to
              // a local variable. Put a literal undefined param again
              js.UndefinedParam()(toIRType(sym.tpe))
            } else {
              js.VarRef(encodeLocalSym(sym, freshName),
                  mutable = sym.isMutable)(toIRType(sym.tpe))
            }
          } else {
            sys.error("Cannot use package as value: " + tree)
          }

        case Literal(value) =>
          value.tag match {
            case UnitTag =>
              js.Undefined()
            case BooleanTag =>
              js.BooleanLiteral(value.booleanValue)
            case ByteTag | ShortTag | CharTag | IntTag =>
              js.IntLiteral(value.intValue)
            case LongTag =>
              // Convert literal to triplet (at compile time!)
              val (l,m,h) = JSConversions.scalaLongToTriplet(value.longValue)
              genLongModuleCall("apply", js.IntLiteral(l), js.IntLiteral(m), js.IntLiteral(h))
            case FloatTag | DoubleTag =>
              js.DoubleLiteral(value.doubleValue)
            case StringTag =>
              js.StringLiteral(value.stringValue)
            case NullTag =>
              js.Null()
            case ClazzTag =>
              genClassConstant(value.typeValue)
            case EnumTag =>
              genStaticMember(value.symbolValue)
          }

        /** Block that appeared as the result of a translated match
         *  Such blocks are recognized by having at least one element that is
         *  a so-called case-label-def.
         *  The method `genTranslatedMatch()` takes care of compiling the
         *  actual match.
         */
        case Block(stats, expr) if (expr +: stats) exists isCaseLabelDef =>
          /* The assumption is once we encounter a case, the remainder of the
           * block will consist of cases.
           * The prologue may be empty, usually it is the valdef that stores
           * the scrut.
           */
          val (prologue, cases) = stats span (s => !isCaseLabelDef(s))
          assert((expr +: cases) forall isCaseLabelDef,
              "Assumption on the form of translated matches broken: " + tree)

          val translatedMatch =
            genTranslatedMatch(cases map (_.asInstanceOf[LabelDef]),
                expr.asInstanceOf[LabelDef])

          js.Block((prologue map genStat) :+ translatedMatch)

        /** Normal block */
        case Block(stats, expr) =>
          val statements = stats map genStat
          val expression = genExpr(expr)
          js.Block(statements :+ expression)

        case Typed(Super(_, _), _) =>
          genExpr(This(currentClassSym))

        case Typed(expr, _) =>
          genExpr(expr)

        case Assign(_, _) =>
          statToExpr(genStat(tree))

        /** Array constructor */
        case av: ArrayValue =>
          genArrayValue(av)

        /** A Match reaching the backend is supposed to be optimized as a switch */
        case mtch: Match =>
          genMatch(mtch)

        /** Anonymous function (only with -Ydelambdafy:method) */
        case fun: Function =>
          genAnonFunction(fun)

        case EmptyTree =>
          // TODO Hum, I do not think this is OK
          js.Undefined()

        case _ =>
          abort("Unexpected tree in genExpr: " +
              tree + "/" + tree.getClass + " at: " + tree.pos)
      }
    } // end of GenJSCode.genExpr()

    /** Gen JS code for LabelDef
     *  The only LabelDefs that can reach here are the desugaring of
     *  while and do..while loops. All other LabelDefs (for tail calls or
     *  matches) are caught upstream and transformed in ad hoc ways.
     *
     *  So here we recognize all the possible forms of trees that can result
     *  of while or do..while loops, and we reconstruct the loop for emission
     *  to JS.
     */
    def genLabelDef(tree: LabelDef): js.Tree = {
      implicit val pos = tree.pos
      val sym = tree.symbol

      tree match {
        // while (cond) { body }
        case LabelDef(lname, Nil,
            If(cond,
                Block(bodyStats, Apply(target @ Ident(lname2), Nil)),
                Literal(_))) if (target.symbol == sym) =>
          statToExpr(js.While(genExpr(cond), js.Block(bodyStats map genStat)))

        // while (cond) { body }; result
        case LabelDef(lname, Nil,
            Block(List(
                If(cond,
                    Block(bodyStats, Apply(target @ Ident(lname2), Nil)),
                    Literal(_))),
                result)) if (target.symbol == sym) =>
          js.Block(
              js.While(genExpr(cond), js.Block(bodyStats map genStat)),
              genExpr(result))

        // while (true) { body }
        case LabelDef(lname, Nil,
            Block(bodyStats,
                Apply(target @ Ident(lname2), Nil))) if (target.symbol == sym) =>
          statToExpr(js.While(js.BooleanLiteral(true),
              js.Block(bodyStats map genStat)))

        // while (false) { body }
        case LabelDef(lname, Nil, Literal(Constant(()))) =>
          js.Skip()

        // do { body } while (cond)
        case LabelDef(lname, Nil,
            Block(bodyStats,
                If(cond,
                    Apply(target @ Ident(lname2), Nil),
                    Literal(_)))) if (target.symbol == sym) =>
          statToExpr(js.DoWhile(js.Block(bodyStats map genStat), genExpr(cond)))

        // do { body } while (cond); result
        case LabelDef(lname, Nil,
            Block(
                bodyStats :+
                If(cond,
                    Apply(target @ Ident(lname2), Nil),
                    Literal(_)),
                result)) if (target.symbol == sym) =>
          js.Block(
              js.DoWhile(js.Block(bodyStats map genStat), genExpr(cond)),
              genExpr(result))

        case _ =>
          abort("Found unknown label def at "+tree.pos+": "+tree)
      }
    }

    /** Gen JS code for a try..catch or try..finally block
     *
     *  try..finally blocks are compiled straightforwardly to try..finally
     *  blocks of JS.
     *
     *  try..catch blocks are a little more subtle, as JS does not have
     *  type-based selection of exceptions to catch. We thus encode explicitly
     *  the type tests, like in:
     *
     *  try { ... }
     *  catch (e) {
     *    if (e.isInstanceOf[IOException]) { ... }
     *    else if (e.isInstanceOf[Exception]) { ... }
     *    else {
     *      throw e; // default, re-throw
     *    }
     *  }
     */
    def genTry(tree: Try): js.Tree = {
      implicit val jspos = tree.pos
      val Try(block, catches, finalizer) = tree

      val blockAST = genExpr(block)
      val exceptIdent = js.Ident(freshName("ex"))
      val exceptVar = js.VarRef(exceptIdent, mutable = true)(jstpe.AnyType)
      val resultType = toIRType(tree.tpe)

      val handlerAST = {
        if (catches.isEmpty) {
          js.EmptyTree
        } else {
          val mightCatchJavaScriptException = catches.exists { caseDef =>
            caseDef.pat match {
              case Typed(Ident(nme.WILDCARD), tpt) =>
                isMaybeJavaScriptException(tpt.tpe)
              case Ident(nme.WILDCARD) =>
                true
              case pat @ Bind(_, _) =>
                isMaybeJavaScriptException(pat.symbol.tpe)
            }
          }

          val elseHandler: js.Tree =
            if (mightCatchJavaScriptException)
              js.Throw(js.CallHelper("unwrapJavaScriptException", exceptVar)(jstpe.AnyType))
            else
              js.Throw(exceptVar)

          val handler0 = catches.foldRight(elseHandler) { (caseDef, elsep) =>
            implicit val jspos = caseDef.pos
            val CaseDef(pat, _, body) = caseDef

            // Extract exception type and variable
            val (tpe, boundVar) = (pat match {
              case Typed(Ident(nme.WILDCARD), tpt) =>
                (tpt.tpe, None)
              case Ident(nme.WILDCARD) =>
                (ThrowableClass.tpe, None)
              case Bind(_, _) =>
                (pat.symbol.tpe, Some(encodeLocalSym(pat.symbol, freshName)))
            })

            // Generate the body that must be executed if the exception matches
            val bodyWithBoundVar = (boundVar match {
              case None => genExpr(body)
              case Some(bv) =>
                js.Block(
                    js.VarDef(bv, toIRType(tpe), mutable = false, exceptVar),
                    genExpr(body))
            })

            // Generate the test
            if (tpe == ThrowableClass.tpe) {
              bodyWithBoundVar
            } else {
              val cond = genIsInstanceOf(tpe, exceptVar)
              js.If(cond, bodyWithBoundVar, elsep)(resultType)
            }
          }

          if (mightCatchJavaScriptException) {
            js.Block(
                js.Assign(exceptVar,
                    js.CallHelper("wrapJavaScriptException", exceptVar)(
                        encodeClassType(ThrowableClass))),
                handler0)
          } else {
            handler0
          }
        }
      }

      val finalizerAST = genStat(finalizer) match {
        case js.Skip() => js.EmptyTree
        case ast => ast
      }

      if (handlerAST == js.EmptyTree && finalizerAST == js.EmptyTree) blockAST
      else js.Try(blockAST, exceptIdent, handlerAST, finalizerAST)(resultType)
    }

    /** Gen JS code for an Apply node (method call)
     *
     *  There's a whole bunch of varieties of Apply nodes: regular method
     *  calls, super calls, constructor calls, isInstanceOf/asInstanceOf,
     *  primitives, JS calls, etc. They are further dispatched in here.
     */
    def genApply(tree: Tree): js.Tree = {
      implicit val pos = tree.pos

      tree match {
        /** isInstanceOf and asInstanceOf
         *  The two only methods that keep their type argument until the
         *  backend.
         */
        case Apply(TypeApply(fun, targs), _) =>
          val sym = fun.symbol
          val cast = sym match {
            case Object_isInstanceOf => false
            case Object_asInstanceOf => true
            case _ =>
              abort("Unexpected type application " + fun +
                  "[sym: " + sym.fullName + "]" + " in: " + tree)
          }

          val Select(obj, _) = fun
          val to = targs.head.tpe
          val l = toTypeKind(obj.tpe)
          val r = toTypeKind(to)
          val source = genExpr(obj)

          if (l.isValueType && r.isValueType) {
            if (cast)
              genConversion(l, r, source)
            else
              js.BooleanLiteral(l == r)
          }
          else if (l.isValueType) {
            val result = if (cast) {
              val ctor = ClassCastExceptionClass.info.member(
                  nme.CONSTRUCTOR).suchThat(_.tpe.params.isEmpty)
              js.Throw(genNew(ClassCastExceptionClass, ctor, Nil))
            } else {
              js.BooleanLiteral(false)
            }
            js.Block(source, result) // eval and discard source
          }
          else if (r.isValueType && cast) {
            // Erasure should have added an unboxing operation to prevent that.
            assert(false, tree)
            source
          }
          else if (r.isValueType)
            genIsInstanceOf(boxedClass(to.typeSymbol).tpe, source)
          else if (cast)
            genAsInstanceOf(to, source)
          else
            genIsInstanceOf(to, source)

        /** Super call of the form Class.super[mix].fun(args)
         *  This does not include calls defined in mixin traits, as these are
         *  already desugared by the 'mixin' phase. Only calls to super
         *  classes remain.
         *  Since a class has exactly one direct superclass, and calling a
         *  method two classes above the current one is invalid, I believe
         *  the `mix` item is irrelevant.
         */
        case Apply(fun @ Select(sup @ Super(_, mix), _), args) =>
          if (settings.debug.value)
            log("Call to super: " + tree)

          /* We produce a desugared JavaScript super call immediately,
           * because we might have to use the special `methodTailJumpThisSym`
           * instead of the js.This() that would be output by the JavaScript
           * desugaring.
           */
          val superCall = {
            val thisArg = {
              implicit val pos = sup.pos
              if (methodTailJumpThisSym.get == NoSymbol)
                js.This()(currentClassType)
              else
                js.VarRef(encodeLocalSym(methodTailJumpThisSym, freshName),
                    mutable = false)(currentClassType)
            }
            genStaticApplyMethod(thisArg, fun.symbol, args map genExpr)
          }

          // We initialize the module instance just after the super constructor
          // call.
          if (isStaticModule(currentClassSym) && !isModuleInitialized &&
              currentMethodSym.isClassConstructor) {
            isModuleInitialized = true
            val thisType = jstpe.ClassType(encodeClassFullName(currentClassSym))
            val initModule = js.StoreModule(thisType, js.This()(thisType))
            js.Block(superCall, initModule, js.This()(thisType))
          } else {
            superCall
          }

        /** Constructor call (new)
         *  Further refined into:
         *  * new String(...)
         *  * new of a hijacked boxed class
         *  * new of a primitive JS type
         *  * new Array
         *  * regular new
         */
        case app @ Apply(fun @ Select(New(tpt), nme.CONSTRUCTOR), args) =>
          val ctor = fun.symbol
          if (settings.debug.value)
            assert(ctor.isClassConstructor,
                   "'new' call to non-constructor: " + ctor.name)

          val tpe = tpt.tpe
          if (isStringType(tpe)) {
            genNewString(app)
          } else if (isHijackedBoxedClass(tpe.typeSymbol)) {
            genNewHijackedBoxedClass(tpe.typeSymbol, ctor, args map genExpr)
          } else if (translatedAnonFunctions contains tpe.typeSymbol) {
            val (functionMaker, funInfo) = translatedAnonFunctions(tpe.typeSymbol)
            currentMethodInfoBuilder.createsAnonFunction(funInfo)
            functionMaker(args map genExpr)
          } else if (isRawJSType(tpe)) {
            genPrimitiveJSNew(app)
          } else {
            val arguments = args map genExpr

            val generatedType = toTypeKind(tpt.tpe)
            if (settings.debug.value)
              assert(generatedType.isReferenceType || generatedType.isArrayType,
                   "Non reference type cannot be instantiated: " + generatedType)

            (generatedType: @unchecked) match {
              case arr @ ARRAY(elem) =>
                genNewArray(tpt.tpe, arr.dimensions, arguments)

              case rt @ REFERENCE(cls) =>
                genNew(cls, ctor, arguments)
            }
          }

        /** All other Applys, which cannot be refined by pattern matching
         *  They are further refined by properties of the method symbol.
         */
        case app @ Apply(fun, args) =>
          val sym = fun.symbol

          /** Jump to a label
           *  Most label-applys are catched upstream (while and do..while
           *  loops, jumps to next case of a pattern match), but some are
           *  still handled here:
           *  * Recursive tail call
           *  * Jump to the end of a pattern match
           */
          if (sym.isLabel) {
            /** Recursive tail call
             *  Basically this compiled into
             *  continue tailCallLoop;
             *  but arguments need to be updated beforehand.
             *
             *  Since the rhs for the new value of an argument can depend on
             *  the value of another argument (and since deciding if it is
             *  indeed the case is impossible in general), new values are
             *  computed in temporary variables first, then copied to the
             *  actual variables representing the argument.
             *
             *  Trivial assignments (arg1 = arg1) are eliminated.
             *
             *  If, after elimination of trivial assignments, only one
             *  assignment remains, then we do not use a temporary variable
             *  for this one.
             */
            if (sym == methodTailJumpLabelSym.get) {
              // Prepare triplets of (formalArg, tempVar, actualArg)
              // Do not include trivial assignments (when actualArg == formalArg)
              val formalArgs = methodTailJumpFormalArgs.get
              val actualArgs = args map genExpr
              val quadruplets = {
                for {
                  (formalArgSym, actualArg) <- formalArgs zip actualArgs
                  formalArg = encodeLocalSym(formalArgSym, freshName)
                  if (actualArg match {
                    case js.VarRef(`formalArg`, _) => false
                    case _ => true
                  })
                } yield {
                  (formalArg, toIRType(formalArgSym.tpe),
                      js.Ident(freshName("temp$" + formalArg.name), None),
                      actualArg)
                }
              }

              // The actual jump (continue tailCallLoop;)
              val tailJump = js.Continue(Some(js.Ident("tailCallLoop")))

              quadruplets match {
                case Nil => tailJump

                case (formalArg, argType, _, actualArg) :: Nil =>
                  js.Block(js.Assign(
                      js.VarRef(formalArg, mutable = true)(argType), actualArg),
                      tailJump)

                case _ =>
                  val tempAssignments =
                    for ((_, argType, tempArg, actualArg) <- quadruplets)
                      yield js.VarDef(tempArg, argType, mutable = false, actualArg)
                  val trueAssignments =
                    for ((formalArg, argType, tempArg, _) <- quadruplets)
                      yield js.Assign(
                          js.VarRef(formalArg, mutable = true)(argType),
                          js.VarRef(tempArg, mutable = false)(argType))
                  js.Block(tempAssignments ++ trueAssignments :+ tailJump)
              }
            } else // continues after the comment
            /** Jump the to the end-label of a pattern match
             *  Such labels have exactly one argument, which is the result of
             *  the pattern match (of type Unit if the match is in statement
             *  position). We simply `return` the argument as the result of the
             *  labeled block surrounding the match.
             */
            if (sym.name.toString() startsWith "matchEnd") {
              val labelIdent = encodeLabelSym(sym, freshName)
              js.Return(genExpr(args.head), Some(labelIdent))
            } else {
              /* No other label apply should ever happen. If it does, then we
               * have missed a pattern of LabelDef/LabelApply and some new
               * translation must be found for it.
               */
              abort("Found unknown label apply at "+tree.pos+": "+tree)
            }
          } else // continues after the comment
          /** Primitive method whose code is generated by the codegen */
          if (scalaPrimitives.isPrimitive(sym)) {
            // primitive operation
            genPrimitiveOp(app)
          } else if (currentRun.runDefinitions.isBox(sym)) {
            /** Box a primitive value */
            val arg = args.head
            makePrimitiveBox(genExpr(arg), arg.tpe)
          } else if (currentRun.runDefinitions.isUnbox(sym)) {
            /** Unbox a primitive value */
            val arg = args.head
            makePrimitiveUnbox(genExpr(arg), tree.tpe)
          } else {
            /** Actual method call
             *  But even these are further refined into:
             *  * Methods of java.lang.Object (because things typed as such
             *    at compile-time are sometimes raw JS values at runtime).
             *  * Methods of ancestors of java.lang.String (because they could
             *    be a primitive string at runtime).
             *  * Likewise, methods of ancestors of hijacked boxed classes
             *  * Calls to primitive JS methods (Scala.js -> JS bridge)
             *  * Regular method call
             */
            if (settings.debug.value)
              log("Gen CALL_METHOD with sym: " + sym + " isStaticSymbol: " + sym.isStaticMember);

            val Select(receiver, _) = fun

            if (MethodWithHelperInEnv contains fun.symbol) {
              if (!isRawJSType(receiver.tpe)) {
                currentMethodInfoBuilder.callsMethod(receiver.tpe.typeSymbol,
                    encodeMethodSym(fun.symbol))
              }
              val helper = MethodWithHelperInEnv(fun.symbol)
              val arguments = (receiver :: args) map genExpr
              js.CallHelper(helper, arguments:_*)(toIRType(tree.tpe))
            } else if (ToStringMaybeOnHijackedClass contains fun.symbol) {
              js.Cast(js.JSApply(js.JSDotSelect(
                  js.Cast(genExpr(receiver), jstpe.DynType),
                  js.Ident("toString")), Nil), toIRType(tree.tpe))
            } else if (isStringType(receiver.tpe)) {
              genStringCall(app)
            } else if (isRawJSType(receiver.tpe)) {
              genPrimitiveJSCall(app)
            } else if (foreignIsImplClass(sym.owner)) {
              genTraitImplApply(sym, args map genExpr)
            } else {
              val instance = genExpr(receiver)
              val arguments = args map genExpr

              if (sym.isClassConstructor) {
                /* See #66: we have to emit a static call to avoid calling a
                 * constructor with the same signature in a subclass */
                genStaticApplyMethod(instance, sym, arguments)
              } else {
                genApplyMethod(instance, receiver.tpe, sym, arguments)
              }
            }
          }
      }
    }

    def genStaticApplyMethod(receiver: js.Tree, method: Symbol,
        arguments: List[js.Tree])(implicit pos: Position): js.Tree = {
      val classIdent = encodeClassFullNameIdent(method.owner)
      val methodIdent = encodeMethodSym(method)
      currentMethodInfoBuilder.callsMethodStatic(classIdent, methodIdent)
      js.StaticApply(receiver, jstpe.ClassType(classIdent.name), methodIdent,
          arguments)(toIRType(method.tpe.resultType))
    }

    def genTraitImplApply(method: Symbol, arguments: List[js.Tree])(
        implicit pos: Position): js.Tree = {
      val implIdent = encodeClassFullNameIdent(method.owner)
      val methodIdent = encodeMethodSym(method)
      genTraitImplApply(implIdent, methodIdent, arguments,
          toIRType(method.tpe.resultType))
    }

    def genTraitImplApply(implIdent: js.Ident, methodIdent: js.Ident,
        arguments: List[js.Tree], resultType: jstpe.Type)(
        implicit pos: Position): js.Tree = {
      currentMethodInfoBuilder.callsMethod(implIdent, methodIdent)
      js.TraitImplApply(jstpe.ClassType(implIdent.name), methodIdent,
          arguments)(resultType)
    }

    private lazy val ToStringMaybeOnHijackedClass: Set[Symbol] =
      (Set(CharSequenceClass, StringClass, NumberClass) ++ HijackedBoxedClasses)
        .map(cls => getMemberMethod(cls, nme.toString_))

    private lazy val MethodWithHelperInEnv: Map[Symbol, String] = {
      val m = mutable.Map[Symbol, String](
        Object_toString  -> "objectToString",
        Object_getClass  -> "objectGetClass",
        Object_clone     -> "objectClone",
        Object_finalize  -> "objectFinalize",
        Object_notify    -> "objectNotify",
        Object_notifyAll -> "objectNotifyAll",
        Object_equals    -> "objectEquals",
        Object_hashCode  -> "objectHashCode"
      )

      def addN(clazz: Symbol, meth: TermName, helperName: String): Unit = {
        for (sym <- getMemberMethod(clazz, meth).alternatives)
          m += sym -> helperName
      }
      def addS(clazz: Symbol, meth: String, helperName: String): Unit =
        addN(clazz, newTermName(meth), helperName)

      addS(CharSequenceClass, "length", "charSequenceLength")
      addS(CharSequenceClass, "charAt", "charSequenceCharAt")
      addS(CharSequenceClass, "subSequence", "charSequenceSubSequence")

      addS(ComparableClass, "compareTo", "comparableCompareTo")

      for (clazz <- StringClass +: HijackedBoxedClasses) {
        addN(clazz, nme.equals_, "objectEquals")
        addN(clazz, nme.hashCode_, "objectHashCode")
        if (clazz != BoxedUnitClass)
          addS(clazz, "compareTo", "comparableCompareTo")
      }

      for (clazz <- NumberClass +: HijackedNumberClasses) {
        for (pref <- Seq("byte", "short", "int", "long", "float", "double")) {
          val meth = pref+"Value"
          addS(clazz, meth, "number"+meth.capitalize)
          // example: "intValue" -> "numberIntValue"
        }
      }

      addS(BoxedFloatClass, "isNaN", "isNaN")
      addS(BoxedDoubleClass, "isNaN", "isNaN")

      addS(BoxedFloatClass, "isInfinite", "isInfinite")
      addS(BoxedDoubleClass, "isInfinite", "isInfinite")

      m.toMap
    }

    private lazy val CharSequenceClass = requiredClass[java.lang.CharSequence]

    /** Gen JS code for a conversion between primitive value types */
    def genConversion(from: TypeKind, to: TypeKind, value: js.Tree)(
        implicit pos: Position): js.Tree = {
      def int0 = js.IntLiteral(0)
      def int1 = js.IntLiteral(1)
      def float0 = js.DoubleLiteral(0.0)
      def float1 = js.DoubleLiteral(1.0)

      (from, to) match {
        case (LongKind, BOOL) =>
          genLongCall(value, "notEquals", genLongModuleCall("zero"))
        case (_:INT,   BOOL) => js.BinaryOp("!==", value, int0,   jstpe.BooleanType)
        case (_:FLOAT, BOOL) => js.BinaryOp("!==", value, float0, jstpe.BooleanType)

        case (BOOL, LongKind) =>
          js.If(value, genLongModuleCall("one"), genLongModuleCall("zero"))(
              jstpe.ClassType(ir.Definitions.RuntimeLongClass))
        case (BOOL, _:INT)   => js.If(value, int1,   int0  )(jstpe.IntType)
        case (BOOL, _:FLOAT) => js.If(value, float1, float0)(jstpe.DoubleType)

        // TODO Isn't float-to-int missing?

        case _ => value
      }
    }

    /** Gen JS code for an isInstanceOf test (for reference types only) */
    def genIsInstanceOf(to: Type, value: js.Tree)(
        implicit pos: Position): js.Tree = {

      def genTypeOfTest(typeString: String) = {
        js.BinaryOp("===",
            js.UnaryOp("typeof", value, jstpe.StringType),
            js.StringLiteral(typeString),
            jstpe.BooleanType)
      }

      if (isRawJSType(to)) {
        to.typeSymbol match {
          case JSNumberClass    => genTypeOfTest("number")
          case JSStringClass    => genTypeOfTest("string")
          case JSBooleanClass   => genTypeOfTest("boolean")
          case JSUndefinedClass => genTypeOfTest("undefined")
          case sym if sym.isTrait =>
            currentUnit.error(pos,
                s"isInstanceOf[${sym.fullName}] not supported because it is a raw JS trait")
            js.BooleanLiteral(true)
          case sym =>
            js.BinaryOp("instanceof", value, genGlobalJSObject(sym),
                jstpe.BooleanType)
        }
      } else {
        val (irType, sym) = encodeReferenceType(to)
        if (sym != RuntimeLongClass)
          currentMethodInfoBuilder.accessesClassData(sym)
        js.IsInstanceOf(value, irType)
      }
    }

    /** Gen JS code for an asInstanceOf cast (for reference types only) */
    def genAsInstanceOf(to: Type, value: js.Tree)(
        implicit pos: Position): js.Tree = {

      def default: js.Tree = {
        val (irType, sym) = encodeReferenceType(to)
        currentMethodInfoBuilder.accessesClassData(sym)
        js.AsInstanceOf(value, irType)
      }

      if (isRawJSType(to)) {
        // asInstanceOf on JavaScript is completely erased
        if (value.tpe == jstpe.DynType) value
        else js.Cast(value, jstpe.DynType)
      } else if (FunctionClass.seq contains to.typeSymbol) {
        /* Don't hide a JSFunctionToScala inside a useless cast, otherwise
         * the optimization avoiding double-wrapping in genApply() will not
         * be able to kick in.
         */
        value match {
          case JSFunctionToScala(fun, _) => value
          case _                         => default
        }
      } else {
        default
      }
    }

    /** Gen JS code for a call to a Scala method.
     *  This also registers that the given method is called by the current
     *  method in the method info builder.
     */
    def genApplyMethod(receiver: js.Tree, receiverType: Type,
        methodSym: Symbol, arguments: List[js.Tree])(
        implicit pos: Position): js.Tree = {
      genApplyMethod(receiver, receiverType.typeSymbol, methodSym, arguments)
    }

    /** Gen JS code for a call to a Scala method.
     *  This also registers that the given method is called by the current
     *  method in the method info builder.
     */
    def genApplyMethod(receiver: js.Tree, receiverTypeSym: Symbol,
        methodSym: Symbol, arguments: List[js.Tree])(
        implicit pos: Position): js.Tree = {
      genApplyMethod(receiver, receiverTypeSym,
          encodeMethodSym(methodSym), arguments,
          toIRType(methodSym.tpe.resultType))
    }

    /** Gen JS code for a call to a Scala method.
     *  This also registers that the given method is called by the current
     *  method in the method info builder.
     */
    def genApplyMethod(receiver: js.Tree, receiverType: Type,
        methodIdent: js.Ident, arguments: List[js.Tree], resultType: jstpe.Type)(
        implicit pos: Position): js.Tree = {
      genApplyMethod(receiver, receiverType.typeSymbol, methodIdent,
          arguments, resultType)
    }

    /** Gen JS code for a call to a Scala method.
     *  This also registers that the given method is called by the current
     *  method in the method info builder.
     */
    def genApplyMethod(receiver: js.Tree, receiverTypeSym: Symbol,
        methodIdent: js.Ident, arguments: List[js.Tree], resultType: jstpe.Type)(
        implicit pos: Position): js.Tree = {
      currentMethodInfoBuilder.callsMethod(receiverTypeSym, methodIdent)
      js.Apply(receiver, methodIdent, arguments)(resultType)
    }

    /** Gen JS code for a call to a Scala class constructor
     *  This first calls the only JS constructor for the class, which creates
     *  the fields of the instance, initialized to the zero of their respective
     *  types.
     *  Then we call the <init> method containing the code of the particular
     *  overload of the Scala constructors. Since this method returns `this`,
     *  we simply chain the calls.
     *
     *  This also registers that the given class is instantiated by the current
     *  method, and that the given constructor is called, in the method info
     *  builder.
     */
    def genNew(clazz: Symbol, ctor: Symbol, arguments: List[js.Tree])(
        implicit pos: Position): js.Tree = {
      if (clazz.isAnonymousFunction)
        instantiatedAnonFunctions += clazz
      assert(!isRawJSFunctionDef(clazz),
          s"Trying to instantiate a raw JS function def $clazz")
      val ctorIdent = encodeMethodSym(ctor)
      currentMethodInfoBuilder.instantiatesClass(clazz)
      currentMethodInfoBuilder.callsMethod(clazz, ctorIdent)
      js.New(jstpe.ClassType(encodeClassFullName(clazz)),
          ctorIdent, arguments)
    }

    /** Gen JS code for a call to a constructor of a hijacked boxed class.
     *  All of these have 2 constructors: one with the primitive
     *  value, which is erased, and one with a String, which is
     *  equivalent to BoxedClass.valueOf(arg).
     */
    private def genNewHijackedBoxedClass(clazz: Symbol, ctor: Symbol,
        arguments: List[js.Tree])(implicit pos: Position): js.Tree = {
      assert(arguments.size == 1)
      if (isStringType(ctor.tpe.params.head.tpe)) {
        // BoxedClass.valueOf(arg)
        val companion = clazz.companionModule.moduleClass
        val valueOf = getMemberMethod(companion, nme.valueOf) suchThat { s =>
          s.tpe.params.size == 1 && isStringType(s.tpe.params.head.tpe)
        }
        genApplyMethod(genLoadModule(companion), companion, valueOf, arguments)
      } else {
        // erased
        arguments.head
      }
    }

    /** Gen JS code for creating a new Array: new Array[T](length)
     *  For multidimensional arrays (dimensions > 1), the arguments can
     *  specify up to `dimensions` lengths for the first dimensions of the
     *  array.
     */
    def genNewArray(arrayType: Type, dimensions: Int,
        arguments: List[js.Tree])(implicit pos: Position): js.Tree = {
      val argsLength = arguments.length

      if (argsLength > dimensions)
        abort("too many arguments for array constructor: found " + argsLength +
          " but array has only " + dimensions + " dimension(s)")

      val (irType, sym) = encodeArrayType(arrayType)
      currentMethodInfoBuilder.accessesClassData(sym)
      js.NewArray(irType, arguments)
    }

    /** Gen JS code for an array literal
     *  We generate a JS array construction that we wrap in a native array
     *  wrapper.
     */
    def genArrayValue(tree: Tree): js.Tree = {
      implicit val pos = tree.pos
      val ArrayValue(tpt @ TypeTree(), elems) = tree

      val (irType, sym) = encodeArrayType(tree.tpe)
      currentMethodInfoBuilder.accessesClassData(sym)
      js.ArrayValue(irType, elems map genExpr)
    }

    /** Gen JS code for a Match, i.e., a switch-able pattern match
     *  Eventually, this is compiled into a JS switch construct. But because
     *  we can be in expression position, and a JS switch cannot be given a
     *  meaning in expression position, we emit a JS "match" construct (which
     *  does not need the `break`s in each case. `JSDesugaring` will transform
     *  that in a switch.
     *
     *  Some caveat here. It may happen that there is a guard in here, despite
     *  the fact that switches cannot have guards (in the JVM nor in JS).
     *  The JVM backend emits a jump to the default clause when a guard is not
     *  fulfilled. We cannot do that. Instead, currently we duplicate the body
     *  of the default case in the else branch of the guard test.
     */
    def genMatch(tree: Tree): js.Tree = {
      implicit val pos = tree.pos
      val Match(selector, cases) = tree

      val expr = genExpr(selector)
      val resultType = toIRType(tree.tpe)

      val List(defaultBody0) = for {
        CaseDef(Ident(nme.WILDCARD), EmptyTree, body) <- cases
      } yield body

      val (defaultBody, defaultLabelSym) = defaultBody0 match {
        case LabelDef(_, Nil, rhs) if hasSynthCaseSymbol(defaultBody0) =>
          (rhs, defaultBody0.symbol)
        case _ =>
          (defaultBody0, NoSymbol)
      }

      var clauses: List[(List[js.Tree], js.Tree)] = Nil
      var elseClause: js.Tree = js.EmptyTree

      for (caze @ CaseDef(pat, guard, body) <- cases) {
        assert(guard == EmptyTree)

        def genBody() = body match {
          // Yes, this will duplicate the default body in the output
          case If(cond, thenp, app @ Apply(_, Nil)) if app.symbol == defaultLabelSym =>
            js.If(genExpr(cond), genExpr(thenp), genExpr(defaultBody))(resultType)(body.pos)
          case If(cond, thenp, Block(List(app @ Apply(_, Nil)), _)) if app.symbol == defaultLabelSym =>
            js.If(genExpr(cond), genExpr(thenp), genExpr(defaultBody))(resultType)(body.pos)

          case _ =>
            genExpr(body)
        }

        pat match {
          case lit: Literal =>
            clauses = (List(genExpr(lit)), genBody()) :: clauses
          case Ident(nme.WILDCARD) =>
            elseClause = genExpr(defaultBody)
          case Alternative(alts) =>
            val genAlts = {
              alts map {
                case lit: Literal => genExpr(lit)
                case _ =>
                  abort("Invalid case in alternative in switch-like pattern match: " +
                      tree + " at: " + tree.pos)
              }
            }
            clauses = (genAlts, genBody()) :: clauses
          case _ =>
            abort("Invalid case statement in switch-like pattern match: " +
                tree + " at: " + (tree.pos))
        }
      }

      js.Match(expr, clauses.reverse, elseClause)(resultType)
    }

    /** Gen JS code for a translated match
     *
     *  This implementation relies heavily on the patterns of trees emitted
     *  by the current pattern match phase (as of Scala 2.10).
     *
     *  The trees output by the pattern matcher are assumed to follow these
     *  rules:
     *  * Each case LabelDef (in `cases`) must not take any argument.
     *  * The last one must be a catch-all (case _ =>) that never falls through.
     *  * Jumps to the `matchEnd` are allowed anywhere in the body of the
     *    corresponding case label-defs, but not outside.
     *  * Jumps to case label-defs are restricted to jumping to the very next
     *    case, and only in positions denoted by <jump> in:
     *    <case-body> ::=
     *        If(_, <case-body>, <case-body>)
     *      | Block(_, <case-body>)
     *      | <jump>
     *      | _
     *    These restrictions, together with the fact that we are in statement
     *    position (thanks to the above transformation), mean that they can be
     *    simply replaced by `skip`.
     *
     *  To implement jumps to `matchEnd`, which have one argument which is the
     *  result of the match, we enclose all the cases in one big labeled block.
     *  Jumps are then compiled as `break`s out of that block if the result has
     *  type Unit, or `return`s out of the block otherwise.
     */
    def genTranslatedMatch(cases: List[LabelDef],
        matchEnd: LabelDef)(implicit pos: Position): js.Tree = {

      val nextCaseSyms = (cases.tail map (_.symbol)) :+ NoSymbol

      val translatedCases = for {
        (LabelDef(_, Nil, rhs), nextCaseSym) <- cases zip nextCaseSyms
      } yield {
        def genCaseBody(tree: Tree): js.Tree = {
          implicit val pos = tree.pos
          tree match {
            case If(cond, thenp, elsep) =>
              js.If(genExpr(cond), genCaseBody(thenp), genCaseBody(elsep))(
                  jstpe.UndefType)

            case Block(stats, expr) =>
              js.Block((stats map genStat) :+ genCaseBody(expr))

            case Apply(_, Nil) if tree.symbol == nextCaseSym =>
              js.Skip()

            case _ =>
              genStat(tree)
          }
        }

        genCaseBody(rhs)
      }

      js.Labeled(encodeLabelSym(matchEnd.symbol, freshName),
          toIRType(matchEnd.tpe), js.Block(translatedCases))
    }

    /** Gen JS code for a primitive method call */
    private def genPrimitiveOp(tree: Apply): js.Tree = {
      import scalaPrimitives._

      implicit val jspos = tree.pos

      val sym = tree.symbol
      val Apply(fun @ Select(receiver, _), args) = tree

      val code = scalaPrimitives.getPrimitive(sym, receiver.tpe)

      if (isArithmeticOp(code) || isLogicalOp(code) || isComparisonOp(code))
        genSimpleOp(tree, receiver :: args, code)
      else if (code == scalaPrimitives.CONCAT)
        genStringConcat(tree, receiver, args)
      else if (code == HASH)
        genScalaHash(tree, receiver)
      else if (isArrayOp(code))
        genArrayOp(tree, code)
      else if (code == SYNCHRONIZED)
        genSynchronized(tree)
      else if (isCoercion(code))
        genCoercion(tree, receiver, code)
      else if (jsPrimitives.isJavaScriptPrimitive(code))
        genJSPrimitive(tree, receiver, args, code)
      else
        abort("Primitive operation not handled yet: " + sym.fullName + "(" +
            fun.symbol.simpleName + ") " + " at: " + (tree.pos))
    }

    /** Gen JS code for a simple operation (arithmetic, logical, or comparison) */
    private def genSimpleOp(tree: Apply, args: List[Tree], code: Int): js.Tree = {
      import scalaPrimitives._

      implicit val jspos = tree.pos

      def needLongConv(ltpe: Type, rtpe: Type) =
        (isLongType(ltpe) || isLongType(rtpe)) &&
        !(toTypeKind(ltpe).isInstanceOf[FLOAT] ||
          toTypeKind(rtpe).isInstanceOf[FLOAT] ||
          isStringType(ltpe) || isStringType(rtpe))

      val sources = args map genExpr

      def resultType = toIRType(tree.tpe)

      sources match {
        // Unary op on long
        case List(source) if isLongType(args.head.tpe) => code match {
            case POS => genLongCall(source, "unary_+")
            case NEG => genLongCall(source, "unary_-")
            case NOT => genLongCall(source, "unary_~")
            case _   => abort("Unknown or invalid op code on Long: " + code)
          }

        // Unary operation
        case List(source) =>
          (code match {
            case POS =>
              js.UnaryOp("+", source, resultType)
            case NEG =>
              js.UnaryOp("-", source, resultType)
            case NOT =>
              js.UnaryOp("~", source, resultType)
            case ZNOT =>
              js.UnaryOp("!", source, resultType)
            case _ =>
              abort("Unknown unary operation code: " + code)
          })

        // Binary operation requiring conversion to Long of both sides
        case List(lsrc, rsrc) if needLongConv(args(0).tpe, args(1).tpe) =>
          def toLong(tree: js.Tree, tpe: Type) = tpe.typeSymbol match {
              case ByteClass  => genLongModuleCall("fromByte",  tree)
              case ShortClass => genLongModuleCall("fromShort", tree)
              case CharClass  => genLongModuleCall("fromChar",  tree)
              case IntClass   => genLongModuleCall("fromInt",   tree)
              case LongClass  => tree
            }

          val ltree = toLong(lsrc, args(0).tpe)
          val rtree = toLong(rsrc, args(1).tpe)
          val rtLongTpe = RuntimeLongClass.tpe

          def genShift(methodName: String): js.Tree = {
            val rtree =
              if (isLongType(args(1).tpe)) genLongCall(rsrc, "toInt")
              else rsrc
            genOlLongCall(ltree, methodName, rtree)(IntTpe)
          }

          code match {
            case ADD => genOlLongCall(ltree, "+",   rtree)(rtLongTpe)
            case SUB => genOlLongCall(ltree, "-",   rtree)(rtLongTpe)
            case MUL => genOlLongCall(ltree, "*",   rtree)(rtLongTpe)
            case DIV => genOlLongCall(ltree, "/",   rtree)(rtLongTpe)
            case MOD => genOlLongCall(ltree, "%",   rtree)(rtLongTpe)
            case OR  => genOlLongCall(ltree, "|",   rtree)(rtLongTpe)
            case XOR => genOlLongCall(ltree, "^",   rtree)(rtLongTpe)
            case AND => genOlLongCall(ltree, "&",   rtree)(rtLongTpe)
            case LT  => genOlLongCall(ltree, "<",   rtree)(rtLongTpe)
            case LE  => genOlLongCall(ltree, "<=",  rtree)(rtLongTpe)
            case GT  => genOlLongCall(ltree, ">",   rtree)(rtLongTpe)
            case GE  => genOlLongCall(ltree, ">=",  rtree)(rtLongTpe)
            case EQ  => genLongCall  (ltree, "equals", rtree)
            case NE  => genLongCall  (ltree, "notEquals", rtree)
            case LSL => genShift("<<")
            case LSR => genShift(">>>")
            case ASR => genShift(">>")
            case _ =>
              abort("Unknown binary operation code: " + code)
          }

        // Binary operation
        case List(lsrc_in, rsrc_in) =>
          def fromLong(tree: js.Tree, tpe: Type) = tpe.typeSymbol match {
            // If we end up with a long, target must be float
            case LongClass => genLongCall(tree, "toDouble")
            case _ => tree
          }

          lazy val leftKind = toTypeKind(args(0).tpe)
          lazy val rightKind = toTypeKind(args(1).tpe)
          lazy val resultKind = toTypeKind(tree.tpe)

          val lsrc = fromLong(lsrc_in, args(0).tpe)
          val rsrc = fromLong(rsrc_in, args(1).tpe)

          def genEquality(eqeq: Boolean, not: Boolean) = {
            if (eqeq &&
                leftKind.isReferenceType &&
                !isRawJSType(args(0).tpe) &&
                !isRawJSType(args(1).tpe) &&
                // don't call equals if we have a literal null at rhs
                !rsrc.isInstanceOf[js.Null]
                ) {
              val body = genEqEqPrimitive(args(0), args(1), lsrc, rsrc)
              if (not) js.UnaryOp("!", body, resultType) else body
            } else
              js.BinaryOp(if (not) "!==" else "===", lsrc, rsrc, resultType)
          }

          (code: @switch) match {
            case EQ => genEquality(eqeq = true, not = false)
            case NE => genEquality(eqeq = true, not = true)
            case ID => genEquality(eqeq = false, not = false)
            case NI => genEquality(eqeq = false, not = true)
            case _ =>
              js.BinaryOp(primCodeToBinaryOp(code), lsrc, rsrc, resultType)
          }

        case _ =>
          abort("Too many arguments for primitive function: " + tree)
      }
    }

    private val primCodeToBinaryOp: Map[Int, String] = {
      import scalaPrimitives._
      Map(
        ADD -> "+" , SUB -> "-", MUL -> "*" , DIV -> "/" , MOD -> "%",
        OR  -> "|" , XOR -> "^", AND -> "&" , LSL -> "<<", LSR -> ">>>",
        ASR -> ">>", LT  -> "<", LE  -> "<=", GT  -> ">" , GE  -> ">=",
        ZOR -> "||", ZAND -> "&&")
    }

    /** Gen JS code for a call to Any.== */
    def genEqEqPrimitive(l: Tree, r: Tree, lsrc: js.Tree, rsrc: js.Tree)(
        implicit pos: Position): js.Tree = {
      /** True if the equality comparison is between values that require the use of the rich equality
        * comparator (scala.runtime.Comparator.equals). This is the case when either side of the
        * comparison might have a run-time type subtype of java.lang.Number or java.lang.Character.
        * When it is statically known that both sides are equal and subtypes of Number of Character,
        * not using the rich equality is possible (their own equals method will do ok.)*/
      def mustUseAnyComparator: Boolean = {
        def areSameFinals = l.tpe.isFinalType && r.tpe.isFinalType && (l.tpe =:= r.tpe)
        !areSameFinals && isMaybeBoxed(l.tpe.typeSymbol) && isMaybeBoxed(r.tpe.typeSymbol)
      }

      val function = if (mustUseAnyComparator) "anyEqEq" else "anyRefEqEq"
      js.CallHelper(function, lsrc, rsrc)(jstpe.BooleanType)
    }

    /** Gen JS code for string concatenation
     *  We explicitly call the JS toString() on any non-String argument to
     *  avoid the weird things happening when adding "things" in JS.
     *  Because any argument can potentially be `null` or `undefined`, we
     *  cannot really call toString() directly. The helper
     *  `anyToStringForConcat` handles these cases properly.
     */
    private def genStringConcat(tree: Apply, receiver: Tree,
        args: List[Tree]): js.Tree = {
      implicit val pos = tree.pos

      /* Primitive number types such as scala.Int have a
       *   def +(s: String): String
       * method, which is why we have to box the lhs sometimes.
       * Otherwise, both lhs and rhs are already reference types (Any of String)
       * so boxing is not necessary (in particular, rhs is never a primitive).
       */
      assert(!isPrimitiveValueType(receiver.tpe) || isStringType(args.head.tpe))
      assert(!isPrimitiveValueType(args.head.tpe))

      val rhs = genExpr(args.head)

      val lhs = {
        val lhs0 = genExpr(receiver)
        // Box the receiver if it is a primitive value
        if (!isPrimitiveValueType(receiver.tpe)) lhs0
        else makePrimitiveBox(lhs0, receiver.tpe)
      }

      js.BinaryOp("+", lhs, rhs, jstpe.StringType)
    }

    /** Gen JS code for a call to Any.## */
    private def genScalaHash(tree: Apply, receiver: Tree): js.Tree = {
      implicit val jspos = tree.pos

      val instance = genLoadModule(ScalaRunTimeModule)
      val arguments = List(genExpr(receiver))
      val sym = getMember(ScalaRunTimeModule, nme.hash_)

      genApplyMethod(instance, ScalaRunTimeModule.moduleClass, sym, arguments)
    }

    /** Gen JS code for an array operation (get, set or length) */
    private def genArrayOp(tree: Tree, code: Int): js.Tree = {
      import scalaPrimitives._

      implicit val pos = tree.pos

      val Apply(Select(arrayObj, _), args) = tree
      val arrayValue = genExpr(arrayObj)
      val arguments = args map genExpr

      if (scalaPrimitives.isArrayGet(code)) {
        // get an item of the array
        if (settings.debug.value)
          assert(args.length == 1,
              s"Array get requires 1 argument, found ${args.length} in $tree")
        js.ArraySelect(arrayValue, arguments(0))(toIRType(tree.tpe))
      } else if (scalaPrimitives.isArraySet(code)) {
        // set an item of the array
        if (settings.debug.value)
          assert(args.length == 2,
              s"Array set requires 2 arguments, found ${args.length} in $tree")
        statToExpr {
          js.Assign(
              js.ArraySelect(arrayValue, arguments(0))(toIRType(tree.tpe)),
              arguments(1))
        }
      } else {
        // length of the array
        js.ArrayLength(arrayValue)
      }
    }

    /** Gen JS code for a call to AnyRef.synchronized */
    private def genSynchronized(tree: Apply): js.Tree = {
      /* JavaScript is single-threaded. I believe we can drop the
       * synchronization altogether.
       */
      genExpr(tree.args.head)
    }

    /** Gen JS code for a coercion */
    private def genCoercion(tree: Apply, receiver: Tree, code: Int): js.Tree = {
      import scalaPrimitives._

      implicit val jspos = tree.pos

      val source = genExpr(receiver)

      (code: @scala.annotation.switch) match {
        // From Long to something
        case L2B =>
          genLongCall(source, "toByte")
        case L2S =>
          genLongCall(source, "toShort")
        case L2C =>
          genLongCall(source, "toChar")
        case L2I =>
          genLongCall(source, "toInt")
        case L2F =>
          genLongCall(source, "toFloat")
        case L2D =>
          genLongCall(source, "toDouble")

        // From something to long
        case B2L =>
          genLongModuleCall("fromByte", source)
        case S2L =>
          genLongModuleCall("fromShort", source)
        case C2L =>
          genLongModuleCall("fromChar", source)
        case I2L =>
          genLongModuleCall("fromInt", source)
        case F2L =>
          genLongModuleCall("fromFloat", source)
        case D2L =>
          genLongModuleCall("fromDouble", source)

        // Conversions to chars (except for Long)
        case B2C | S2C | I2C | F2C | D2C =>
          js.BinaryOp("&", source, js.IntLiteral(0xffff), jstpe.IntType)

        // To Byte, need to crop at signed 8-bit
        case C2B | S2B | I2B | F2B | D2B =>
          // note: & 0xff would not work because of negative values
          js.BinaryOp(">>",
              js.BinaryOp("<<", source, js.IntLiteral(24), jstpe.IntType),
              js.IntLiteral(24), jstpe.IntType)

        // To Short, need to crop at signed 16-bit
        case C2S | I2S | F2S | D2S =>
          // note: & 0xffff would not work because of negative values
          js.BinaryOp(">>",
              js.BinaryOp("<<", source, js.IntLiteral(16), jstpe.IntType),
              js.IntLiteral(16), jstpe.IntType)

        // To Int, need to crop at signed 32-bit
        case F2I | D2I =>
          js.BinaryOp("|", source, js.IntLiteral(0), jstpe.IntType)

        case _ => source
      }
    }

    /** Gen JS code for an ApplyDynamic
     *  ApplyDynamic nodes appear as the result of calls to methods of a
     *  structural type.
     *
     *  Most unfortunately, earlier phases of the compiler assume too much
     *  about the backend, namely, they believe arguments and the result must
     *  be boxed, and do the boxing themselves. This decision should be left
     *  to the backend, but it's not, so we have to undo these boxes.
     *  Note that this applies to parameter types only. The return type is boxed
     *  anyway since we do not know it's exact type.
     *
     *  This then generates a call to the reflective call proxy for the given
     *  arguments.
     */
    private def genApplyDynamic(tree: ApplyDynamic): js.Tree = {
      implicit val pos = tree.pos

      val sym = tree.symbol
      val params = sym.tpe.params

      /** check if the method we are invoking is eq or ne. they cannot be
       *  overridden since they are final. If this is true, we only emit a
       *  `===` or `!==`.
       */
      val isEqOrNeq = (sym.name.decoded == "eq" || sym.name.decoded == "ne") &&
        params.size == 1 && params.head.tpe.typeSymbol == ObjectClass

      /** check if the method we are invoking conforms to the update
       *  method on scala.Array. If this is the case, we have to check
       *  that case specially at runtime, since the arrays element type is not
       *  erased and therefore the method name mangling turns out wrong.
       *
       *  Note that we cannot check if Unit conforms to the expected return
       *  type, since this type information is already erased.
       */
      def isArrayLikeUpdate = sym.name.decoded == "update" && {
        params.size == 2 && params.head.tpe.typeSymbol == IntClass
      }

      /**
       * Tests whether one of our reflective "boxes" for primitive types
       * implements the particular method. If this is the case
       * (result != NoSymbol), we generate a runtime instance check if we are
       * dealing with the appropriate primitive type.
       */
      def matchingSymIn(clazz: Symbol) = clazz.tpe.member(sym.name).suchThat { s =>
        val sParams = s.tpe.params
        !s.isBridge &&
        params.size == sParams.size &&
        (params zip sParams).forall { case (s1,s2) =>
          s1.tpe =:= s2.tpe
        }
      }

      val ApplyDynamic(receiver, args) = tree

      if (isEqOrNeq) {
        // Just emit a boxed equiality check
        val jsThis = genExpr(receiver)
        val jsThat = genExpr(args.head)
        val op = if (sym.name.decoded == "eq") "===" else "!=="

        ensureBoxed(js.BinaryOp(op, jsThis, jsThat, jstpe.BooleanType),
            BooleanClass.tpe)
      } else {
        // Create a fully-fledged reflective call
        val receiverType = toIRType(receiver.tpe)
        val callTrgIdent = js.Ident(freshName("dynCallTrg"))
        val callTrgVarDef =
          js.VarDef(callTrgIdent, receiverType, mutable = false, genExpr(receiver))
        val callTrg = js.VarRef(callTrgIdent, mutable = false)(receiverType)

        val arguments = args zip sym.tpe.params map { case (arg, param) =>
          if (isPrimitiveValueType(param.tpe)) {
            arg match {
              case Apply(_, List(result)) if currentRun.runDefinitions.isBox(arg.symbol) =>
                genExpr(result)
              case _ =>
                makePrimitiveUnbox(genExpr(arg), param.tpe)
            }
          } else {
            genExpr(arg)
          }
        }

        val proxyIdent = encodeMethodSym(sym, reflProxy = true)
        var callStatement: js.Tree =
          genApplyMethod(callTrg, receiver.tpe, proxyIdent, arguments,
              jstpe.AnyType)

        if (isArrayLikeUpdate) {
          val elemIRTpe = toTypeKind(sym.tpe.params(1).tpe).toReferenceType
          val arrIRTpe  = jstpe.ArrayType(elemIRTpe)
          callStatement = js.If(js.IsInstanceOf(callTrg, arrIRTpe), statToExpr {
            val castCallTrg = js.AsInstanceOf(callTrg, arrIRTpe)
            js.Assign(
                js.ArraySelect(castCallTrg, arguments(0))(elemIRTpe),
                arguments(1))
          }, {
            callStatement
          })(jstpe.AnyType)
        }

        for {
          (primTypeOf, reflBoxClass) <- Seq(
              ("string", RuntimeStringClass),
              ("number", NumberReflectiveCallClass),
              ("boolean", BooleanReflectiveCallClass)
          )
          implMethodSym = matchingSymIn(reflBoxClass)
          if implMethodSym != NoSymbol && implMethodSym.isPublic
        } {
          callStatement = js.If(
              js.BinaryOp("===",
                js.UnaryOp("typeof", callTrg, jstpe.StringType),
                js.StringLiteral(primTypeOf),
                jstpe.BooleanType), {
            val helper = MethodWithHelperInEnv.get(implMethodSym)
            if (helper.isDefined) {
              // This method has a helper, call it
              js.CallHelper(helper.get, callTrg :: arguments:_*)(
                  toIRType(implMethodSym.tpe.resultType))
            } else if (implMethodSym.owner == ObjectClass) {
              /* If we end up here, we have a call to a method in
               * java.lang.Object we cannot support (such as wait).
               * Calls like this only fail reflectively at compile time because
               * some of them exist in the Scala stdlib. DCE will issue a
               * warning in any case.
               */
              currentUnit.error(pos,
                  s"""You tried to call ${implMethodSym.name} on AnyRef reflectively, but this
                     |method does not make sense in Scala.js. You may not call it""".stripMargin)
              statToExpr(js.Skip())
            } else {
              if (primTypeOf == "string") {
                val (implClass, methodIdent) =
                  encodeImplClassMethodSym(implMethodSym)
                val retTpe = implMethodSym.tpe.resultType
                val rawApply = genTraitImplApply(
                    encodeClassFullNameIdent(implClass),
                    methodIdent,
                    callTrg :: arguments,
                    toIRType(retTpe))
                // Box the result of the implementing method if required
                if (isPrimitiveValueType(retTpe))
                  makePrimitiveBox(rawApply, retTpe)
                else
                  rawApply
              } else {
                val reflBoxClassPatched = {
                  if (primTypeOf == "number" &&
                      toTypeKind(implMethodSym.tpe.resultType) == DoubleKind &&
                      toTypeKind(sym.tpe.resultType).isInstanceOf[INT]) {
                    // This must be an Int, and not a Double
                    IntegerReflectiveCallClass
                  } else {
                    reflBoxClass
                  }
                }
                val reflBox = genNew(reflBoxClassPatched,
                    reflBoxClassPatched.primaryConstructor, List(callTrg))
                genApplyMethod(
                    reflBox,
                    reflBoxClassPatched,
                    proxyIdent,
                    arguments,
                    jstpe.AnyType)
              }
            }
          }, { // else
            callStatement
          })(jstpe.AnyType)
        }

        js.Block(callTrgVarDef, callStatement)
      }
    }

    /** Ensures that the value of the given tree is boxed.
     *  @param expr Tree to be boxed if needed.
     *  @param tpeEnteringPosterasure The type of `expr` as it was entering
     *    the posterasure phase.
     */
    def ensureBoxed(expr: js.Tree, tpeEnteringPosterasure: Type)(
        implicit pos: Position): js.Tree = {

      tpeEnteringPosterasure match {
        case tpe if isPrimitiveValueType(tpe) =>
          makePrimitiveBox(expr, tpe)

        case tpe: ErasedValueType =>
          val boxedClass = tpe.valueClazz
          val ctor = boxedClass.primaryConstructor
          genNew(boxedClass, ctor, List(expr))

        case _ =>
          expr
      }
    }

    /** Ensures that the value of the given tree is unboxed.
     *  @param expr Tree to be unboxed if needed.
     *  @param tpeEnteringPosterasure The type of `expr` as it was entering
     *    the posterasure phase.
     */
    def ensureUnboxed(expr: js.Tree, tpeEnteringPosterasure: Type)(
        implicit pos: Position): js.Tree = {

      tpeEnteringPosterasure match {
        case tpe if isPrimitiveValueType(tpe) =>
          makePrimitiveUnbox(expr, tpe)

        case tpe: ErasedValueType =>
          val boxedClass = tpe.valueClazz
          val unboxMethod = boxedClass.derivedValueClassUnbox
          genApplyMethod(expr, boxedClass, unboxMethod, Nil)

        case _ =>
          expr
      }
    }

    /** Gen a boxing operation (tpe is the primitive type) */
    def makePrimitiveBox(expr: js.Tree, tpe: Type)(
        implicit pos: Position): js.Tree =
      makePrimitiveBoxUnbox(expr, tpe, unbox = false)

    /** Gen an unboxing operation (tpe is the primitive type) */
    def makePrimitiveUnbox(expr: js.Tree, tpe: Type)(
        implicit pos: Position): js.Tree =
      makePrimitiveBoxUnbox(expr, tpe, unbox = true)

    /** Common implementation for `makeBox()` and `makeUnbox()` */
    private def makePrimitiveBoxUnbox(expr: js.Tree, tpe: Type, unbox: Boolean)(
        implicit pos: Position): js.Tree = {

      toTypeKind(tpe) match {
        case kind: ValueTypeKind =>
          if (unbox) {
            js.CallHelper("u" + kind.primitiveCharCode, expr)(toIRType(tpe))
          } else if (kind == CharKind) {
            js.CallHelper("bC", expr)(encodeClassType(BoxedCharacterClass))
          } else {
            expr // box is identity for all non-Char types
          }
        case _ =>
          abort(s"makePrimitiveBoxUnbox requires a primitive type, found $tpe at $pos")
      }
    }

    private def lookupModuleClass(name: String) = {
      val module = getModuleIfDefined(name)
      if (module == NoSymbol) NoSymbol
      else module.moduleClass
    }

    lazy val ReflectArrayModuleClass = lookupModuleClass("java.lang.reflect.Array")
    lazy val UtilArraysModuleClass = lookupModuleClass("java.util.Arrays")

    /** Gen JS code for a Scala.js-specific primitive method */
    private def genJSPrimitive(tree: Apply, receiver0: Tree,
        args: List[Tree], code: Int): js.Tree = {
      import jsPrimitives._

      implicit val pos = tree.pos

      def receiver = genExpr(receiver0)
      val genArgArray = genPrimitiveJSArgs(tree.symbol, args)

      lazy val js.JSArrayConstr(genArgs) = genArgArray

      def extractFirstArg() = {
        (genArgArray: @unchecked) match {
          case js.JSArrayConstr(firstArg :: otherArgs) =>
            (firstArg, js.JSArrayConstr(otherArgs))
          case js.JSApply(js.JSDotSelect(
              js.JSArrayConstr(firstArg :: firstPart), concat), otherParts) =>
            (firstArg, js.JSApply(js.JSDotSelect(
                js.JSArrayConstr(firstPart), concat), otherParts))
        }
      }

      if (code == DYNNEW) {
        // js.Dynamic.newInstance(clazz)(actualArgs:_*)
        val (jsClass, actualArgArray) = extractFirstArg()
        actualArgArray match {
          case js.JSArrayConstr(actualArgs) =>
            js.JSNew(jsClass, actualArgs)
          case _ =>
            js.CallHelper("newInstanceWithVarargs",
                jsClass, actualArgArray)(jstpe.DynType)
        }
      } else if (code == DYNAPPLY) {
        // js.Dynamic.applyDynamic(methodName)(actualArgs:_*)
        val (methodName, actualArgArray) = extractFirstArg()
        actualArgArray match {
          case js.JSArrayConstr(actualArgs) =>
            js.JSApply(js.JSBracketSelect(receiver, methodName), actualArgs)
          case _ =>
            js.CallHelper("applyMethodWithVarargs",
                receiver, methodName, actualArgArray)(jstpe.DynType)
        }
      } else if (code == DYNLITN) {
        // We have a call of the form:
        //   js.Dynamic.literal(name1 = ..., name2 = ...)
        // Translate to:
        //   {"name1": ..., "name2": ... }
        extractFirstArg() match {
          case (js.StringLiteral("apply", _),
                js.JSArrayConstr(jse.LitNamed(pairs))) =>
            js.JSObjectConstr(pairs)
          case (js.StringLiteral(name, _), _) if name != "apply" =>
            currentUnit.error(pos,
                s"js.Dynamic.literal does not have a method named $name")
            statToExpr(js.Skip())
          case _ =>
            currentUnit.error(pos,
                "js.Dynamic.literal.applyDynamicNamed may not be called directly")
            statToExpr(js.Skip())
        }
      } else if (code == DYNLIT) {
        // We have a call of some other form
        //   js.Dynamic.literal(...)
        // Translate to:
        //   var obj = {};
        //   obj[...] = ...;
        //   obj

        // Extract first arg to future proof against varargs
        extractFirstArg() match {
          // case js.Dynamic.literal("name1" -> ..., "name2" -> ...)
          case (js.StringLiteral("apply", _),
                js.JSArrayConstr(jse.LitNamed(pairs))) =>
            js.JSObjectConstr(pairs)
          // case js.Dynamic.literal(x, y)
          case (js.StringLiteral("apply", _),
                js.JSArrayConstr(tups)) =>

            // Create tmp variable
            val resIdent = js.Ident(freshName("obj"))
            val resVarDef = js.VarDef(resIdent, jstpe.DynType, mutable = false,
                js.JSObjectConstr(Nil))
            val res = js.VarRef(resIdent, mutable = false)(jstpe.DynType)

            // Assign fields
            val tuple2Type = encodeClassType(TupleClass(2))
            val assigns = tups flatMap {
              // special case for literals
              case jse.Tuple2(name, value) =>
                js.Assign(js.JSBracketSelect(res, name), value) :: Nil
              case tupExpr =>
                val tupIdent = js.Ident(freshName("tup"))
                val tup = js.VarRef(tupIdent, mutable = false)(tuple2Type)
                js.VarDef(tupIdent, tuple2Type, mutable = false, tupExpr) ::
                js.Assign(js.JSBracketSelect(res,
                    genApplyMethod(tup, TupleClass(2), js.Ident("$$und1__O"), Nil, jstpe.AnyType)),
                    genApplyMethod(tup, TupleClass(2), js.Ident("$$und2__O"), Nil, jstpe.AnyType)) :: Nil
            }

            js.Block(resVarDef +: assigns :+ res :_*)

          // Here we would need the case where the varargs are passed in
          // as non-literal list:
          //   js.Dynamic.literal(x :_*)
          // However, Scala does currently not support this

          // case where another method is called
          case (js.StringLiteral(name, _), _) if name != "apply" =>
            currentUnit.error(pos,
                s"js.Dynamic.literal does not have a method named $name")
            statToExpr(js.Skip())
          case _ =>
            currentUnit.error(pos,
                "js.Dynamic.literal.applyDynamic may not be called directly")
            statToExpr(js.Skip())
        }
      } else if (code == ARR_CREATE) {
        // js.Array.create(elements:_*)
        genArgArray
      } else if (code == ARRAYCOPY) {
        // System.arraycopy - not a helper because receiver is dropped
        js.CallHelper("systemArraycopy", genArgs)(toIRType(tree.tpe))
      } else (genArgs match {
        case Nil =>
          code match {
            case GETGLOBAL => js.JSGlobal()
            case NTR_MOD_SUFF  =>
              js.StringLiteral(scala.reflect.NameTransformer.MODULE_SUFFIX_STRING)
            case NTR_NAME_JOIN =>
              js.StringLiteral(scala.reflect.NameTransformer.NAME_JOIN_STRING)
            case DEBUGGER =>
              statToExpr(js.Debugger())
            case RETURNRECEIVER =>
              receiver
            case UNITVAL =>
              js.Undefined()
            case UNITTYPE =>
              genClassConstant(UnitTpe)
          }

        case List(arg) =>

          /** get the apply method of a class extending FunctionN
           *
           *  only use when implementing a fromFunctionN primitive
           *  as it uses the tree
           */
          def getFunApply(clSym: Symbol) = {
            // Fetch symbol and resolve overload if necessary
            val sym = getMemberMethod(clSym, newTermName("apply"))

            if (sym.isOverloaded) {
              // The symbol is overloaded. Figure out the arity
              // from the name of the primitive function we are
              // implementing. Then chose the overload with the right
              // number of Object arguments
              val funName = tree.fun.symbol.name.encoded
              assert(funName.startsWith("fromFunction"))
              val arity = funName.substring(12).toInt

              sym.alternatives.find { s =>
                val ps = s.paramss
                ps.size == 1 &&
                ps.head.size == arity &&
                ps.head.forall(_.tpe.typeSymbol == ObjectClass)
              }.get
            } else sym
          }

          def captureWithin(ident: js.Ident, tpe: jstpe.Type, value: js.Tree)(
              within: js.Tree): js.Tree = {
            js.Cast(js.JSApply(
                js.Function(List(js.ParamDef(ident, tpe)), within.tpe,
                    js.Return(within)),
                List(value)), within.tpe)
          }

          code match {
            case V2JS               => statToExpr(exprToStat(arg))
            case Z2JS | N2JS | S2JS => js.Cast(arg, jstpe.DynType)

            /** Convert a scala.FunctionN f to a js.FunctionN
             *  Basically it binds the appropriate `apply` method of f to f.
             *  (function($this) {
             *    return function(args...) {
             *      return $this.apply__something(args...);
             *    }
             *  })(f);
             *
             *  TODO Use the JS function Function.prototype.bind()?
             */
            case F2JS =>
              arg match {
                /* This case will happend every time we have a Scala lambda
                 * in js.FunctionN position. We remove the JS function to
                 * Scala function wrapper, instead of adding a Scala function
                 * to JS function wrapper.
                 */
                case JSFunctionToScala(fun, arity) =>
                  fun

                case _ =>
                  val inputTpe = args.head.tpe
                  val inputIRType = toIRType(inputTpe)
                  val applyMeth = getFunApply(inputTpe.typeSymbol)
                  val arity = applyMeth.tpe.params.size
                  val theFunction = js.Ident("$this")
                  val arguments = (1 to arity).toList map (x => js.Ident("arg"+x))
                  captureWithin(theFunction, inputIRType, arg) {
                    js.Function(arguments.map(js.ParamDef(_, jstpe.AnyType)), jstpe.AnyType, {
                      js.Return(genApplyMethod(
                          js.VarRef(theFunction, mutable = false)(inputIRType),
                          inputTpe, applyMeth,
                          arguments.map(js.VarRef(_, mutable = false)(jstpe.AnyType))))
                    })
                  }
              }

            /** Convert a scala.FunctionN f to a js.ThisFunction{N-1}
             *  Generates:
             *    (function(f) {
             *      return function(args...) {
             *        return f.apply__something(this, args...);
             *      };
             *    })(f);
             */
            case F2JSTHIS =>
              val inputTpe = args.head.tpe
              val inputIRType = toIRType(inputTpe)
              val applyMeth = getFunApply(inputTpe.typeSymbol)
              val arity = applyMeth.tpe.params.size
              val theFunction = js.Ident("f")
              val arguments = (1 until arity).toList map (x => js.Ident("arg"+x))
              captureWithin(theFunction, inputIRType, arg) {
                js.Function(arguments.map(js.ParamDef(_, jstpe.AnyType)), jstpe.AnyType, {
                  js.Return(genApplyMethod(
                      js.VarRef(theFunction, mutable = false)(inputIRType),
                      inputTpe, applyMeth,
                      js.This()(jstpe.AnyType) ::
                      arguments.map(js.VarRef(_, mutable = false)(jstpe.AnyType))))
                })
              }

            case JS2Z | JS2N =>
              makePrimitiveUnbox(arg, tree.tpe)

            case JS2S =>
              genAsInstanceOf(tree.tpe, arg)

            case RTJ2J | J2RTJ =>
              arg // TODO? What if (arg === null) for RTJ2J?

            case DYNSELECT =>
              // js.Dynamic.selectDynamic(arg)
              js.JSBracketSelect(receiver, arg)

            case DICT_DEL =>
              // js.Dictionary.delete(arg)
              js.JSDelete(receiver, arg)

            case ISUNDEF =>
              // js.isUndefined(arg)
              js.BinaryOp("===", arg, js.Undefined(), jstpe.BooleanType)
            case TYPEOF =>
              // js.typeOf(arg)
              js.UnaryOp("typeof", arg, jstpe.StringType)

            case OBJPROPS =>
              // js.Object.properties(arg)
              js.CallHelper("propertiesOf", arg)(jstpe.DynType)
          }

        case List(arg1, arg2) =>
          code match {
            case DYNUPDATE =>
              // js.Dynamic.updateDynamic(arg1)(arg2)
              statToExpr(js.Assign(js.JSBracketSelect(receiver, arg1), arg2))

            case HASPROP =>
              // js.Object.hasProperty(arg1, arg2)
              /* Here we have an issue with evaluation order of arg1 and arg2,
               * since the obvious translation is `arg2 in arg1`, but then
               * arg2 is evaluated before arg1. Since this is not a commonly
               * used operator, we don't try to avoid unnessary temp vars, and
               * simply always evaluate arg1 in a temp before doing the `in`.
               */
              val temp = js.Ident(freshName())
              js.Block(
                  js.VarDef(temp, jstpe.DynType, mutable = false, arg1),
                  js.BinaryOp("in", arg2,
                      js.VarRef(temp, mutable = false)(jstpe.DynType),
                      jstpe.BooleanType))
          }
      })
    }

    /** Gen JS code for a primitive JS call (to a method of a subclass of js.Any)
     *  This is the typed Scala.js to JS bridge feature. Basically it boils
     *  down to calling the method without name mangling. But other aspects
     *  come into play:
     *  * Operator methods are translated to JS operators (not method calls)
     *  * apply is translated as a function call, i.e. o() instead of o.apply()
     *  * Scala varargs are turned into JS varargs (see genPrimitiveJSArgs())
     *  * Getters and parameterless methods are translated as Selects
     *  * Setters are translated to Assigns of Selects
     */
    private def genPrimitiveJSCall(tree: Apply): js.Tree = {
      implicit val pos = tree.pos

      val sym = tree.symbol
      val Apply(fun @ Select(receiver0, _), args0) = tree

      val funName = sym.unexpandedName.decoded
      val receiver = genExpr(receiver0)
      val argArray = genPrimitiveJSArgs(sym, args0)

      // valid only for methods that don't have any varargs
      lazy val js.JSArrayConstr(args) = argArray
      lazy val argc = args.length

      def hasExplicitJSEncoding =
        sym.hasAnnotation(JSNameAnnotation) ||
        sym.hasAnnotation(JSBracketAccessAnnotation)

      val boxedResult = funName match {
        case "unary_+" | "unary_-" | "unary_~" | "unary_!" =>
          assert(argc == 0)
          js.JSUnaryOp(funName.substring(funName.length-1), receiver)

        case "+" | "-" | "*" | "/" | "%" | "<<" | ">>" | ">>>" |
             "&" | "|" | "^" | "&&" | "||" | "<" | ">" | "<=" | ">=" =>
          assert(argc == 1)
          js.JSBinaryOp(funName, receiver, args.head)

        case "apply" if receiver0.tpe.typeSymbol.isSubClass(JSThisFunctionClass) =>
          js.JSApply(js.JSBracketSelect(receiver, js.StringLiteral("call")), args)

        case "apply" if !hasExplicitJSEncoding =>
          /* Protect the receiver so that if the receiver is, e.g.,
           * path.f
           * we emit
           * ScalaJS.protect(path.f)(args...)
           * instead of
           * path.f(args...)
           * where
           * ScalaJS.protect = function(x) { return x; }
           * If we emit the latter, then `this` will be bound to `path` in
           * `f`, which is sometimes extremely harmful (e.g., for builtin
           * methods of `window`).
           */
          def protectedReceiver = receiver match {
            case js.JSDotSelect(_, _) | js.JSBracketSelect(_, _) =>
              js.CallHelper("protect", receiver)(receiver.tpe)
            case _ =>
              receiver
          }
          argArray match {
            case js.JSArrayConstr(args) =>
              js.JSApply(protectedReceiver, args)
            case _ =>
              js.JSApply(js.JSBracketSelect(
                receiver, js.StringLiteral("apply")), List(js.Null(), argArray))
          }

        case _ =>
          def jsFunName = jsNameOf(sym)

          if (sym.hasFlag(reflect.internal.Flags.DEFAULTPARAM)) {
            js.UndefinedParam()(toIRType(sym.tpe.resultType))
          } else if (jsInterop.isJSGetter(sym)) {
            assert(argc == 0)
            js.JSBracketSelect(receiver, js.StringLiteral(jsFunName))
          } else if (jsInterop.isJSSetter(sym)) {
            assert(argc == 1)
            statToExpr(js.Assign(
                js.JSBracketSelect(receiver,
                    js.StringLiteral(jsFunName.stripSuffix("_="))),
                args.head))
          } else if (jsInterop.isJSBracketAccess(sym)) {
            assert(argArray.isInstanceOf[js.JSArrayConstr] && (argc == 1 || argc == 2),
                s"@JSBracketAccess methods should have 1 or 2 non-varargs arguments")
            args match {
              case List(keyArg) =>
                js.JSBracketSelect(receiver, keyArg)
              case List(keyArg, valueArg) =>
                statToExpr(js.Assign(
                    js.JSBracketSelect(receiver, keyArg),
                    valueArg))
            }
          } else {
            argArray match {
              case js.JSArrayConstr(args) =>
                js.JSApply(js.JSBracketSelect(
                    receiver, js.StringLiteral(jsFunName)), args)
              case _ =>
                js.CallHelper("applyMethodWithVarargs", receiver,
                    js.StringLiteral(jsFunName), argArray)(jstpe.DynType)
            }
          }
      }

      boxedResult match {
        case js.UndefinedParam() => boxedResult
        case _ =>
          ensureUnboxed(boxedResult,
              enteringPhase(currentRun.posterasurePhase)(sym.tpe.resultType))
      }
    }

    /** Gen JS code for new java.lang.String(...)
     *  Proxies calls to method newString on object
     *  scala.scalajs.runtime.RuntimeString with proper arguments
     */
    private def genNewString(tree: Apply): js.Tree = {
      implicit val pos = tree.pos
      val Apply(fun @ Select(_, _), args0) = tree

      val ctor = fun.symbol
      val args = args0 map genExpr

      // Filter members of target module for matching member
      val compMembers = for {
        mem <- RuntimeStringModule.tpe.members
        if mem.name.decoded == "newString"
        // Deconstruct method type.
        MethodType(params, returnType) = mem.tpe
        if returnType.typeSymbol == JSStringClass
        // Construct fake type returning java.lang.String
        fakeType = MethodType(params, StringClass.tpe)
        if ctor.tpe.matches(fakeType)
      } yield mem

      if (compMembers.isEmpty) {
        currentUnit.error(pos,
            s"""Could not find implementation for constructor of java.lang.String
               |with type ${ctor.tpe}. Constructors on java.lang.String
               |are forwarded to the companion object of
               |scala.scalajs.runtime.RuntimeString""".stripMargin)
        js.Undefined()
      } else {
        assert(compMembers.size == 1,
            s"""For constructor with type ${ctor.tpe} on java.lang.String,
               |found multiple companion module members.""".stripMargin)

        // Emit call to companion object
        genApplyMethod(
          genLoadModule(RuntimeStringModule),
          RuntimeStringModule.moduleClass,
          compMembers.head,
          args)
      }
    }

    /**
     * Forwards call on java.lang.String to the implementation class of
     * scala.scalajs.runtime.RuntimeString
     */
    private def genStringCall(tree: Apply): js.Tree = {
      implicit val pos = tree.pos

      val sym = tree.symbol

      // Deconstruct tree and create receiver and argument JS expressions
      val Apply(Select(receiver0, _), args0) = tree
      val receiver = js.Cast(genExpr(receiver0), jstpe.DynType)
      val args = args0 map genExpr

      // Get implementation from RuntimeString trait
      val rtStrSym = sym.overridingSymbol(RuntimeStringClass)

      // Check that we found a member
      if (rtStrSym == NoSymbol) {
        currentUnit.error(pos,
            s"""Could not find implementation for method ${sym.name}
               |on java.lang.String with type ${sym.tpe}
               |Methods on java.lang.String are forwarded to the implementation class
               |of scala.scalajs.runtime.RuntimeString""".stripMargin)
        js.Undefined()
      } else {
        assert(!rtStrSym.isOverloaded,
            s"""For method ${sym.name} on java.lang.String with type ${sym.tpe},
               |found multiple implementation class members.""".stripMargin)

        // Emit call to implementation class
        val (implClass, methodIdent) = encodeImplClassMethodSym(rtStrSym)
        genTraitImplApply(
            encodeClassFullNameIdent(implClass),
            methodIdent,
            receiver :: args,
            toIRType(tree.tpe))
      }
    }

    /** Gen JS code for a new of a JS class (subclass of js.Any) */
    private def genPrimitiveJSNew(tree: Apply): js.Tree = {
      implicit val pos = tree.pos

      val Apply(fun @ Select(New(tpt), _), args0) = tree
      val cls = tpt.tpe.typeSymbol
      val ctor = fun.symbol

      genPrimitiveJSArgs(ctor, args0) match {
        case js.JSArrayConstr(args) =>
          if (cls == JSObjectClass && args.isEmpty) js.JSObjectConstr(Nil)
          else js.JSNew(genPrimitiveJSClass(cls), args)
        case argArray =>
          js.CallHelper("newInstanceWithVarargs",
              genPrimitiveJSClass(cls), argArray)(jstpe.DynType)
      }
    }

    /** Gen JS code representing a JS class (subclass of js.Any) */
    private def genPrimitiveJSClass(sym: Symbol)(
        implicit pos: Position): js.Tree = {
      genGlobalJSObject(sym)
    }

    /** Gen JS code representing a JS module (var of the global scope) */
    private def genPrimitiveJSModule(sym: Symbol)(
        implicit pos: Position): js.Tree = {
      genGlobalJSObject(sym)
    }

    /** Gen JS code representing a JS object (class or module) in global scope
     */
    private def genGlobalJSObject(sym: Symbol)(
        implicit pos: Position): js.Tree = {
      jsNameOf(sym).split('.').foldLeft[js.Tree](js.JSGlobal()) { (memo, chunk) =>
        js.JSBracketSelect(memo, js.StringLiteral(chunk, Some(chunk)))
      }
    }

    /** Gen actual actual arguments to a primitive JS call
     *  This handles repeated arguments (varargs) by turning them into
     *  JS varargs, i.e., by expanding them into normal arguments.
     *
     *  Returns an only tree which is a JS array of the arguments. In most
     *  cases, it will be a js.JSArrayConstr with the expanded arguments. It will
     *  not if a Seq is passed to a varargs argument with the syntax seq:_*.
     */
    private def genPrimitiveJSArgs(sym: Symbol, args: List[Tree])(
        implicit pos: Position): js.Tree = {
      val wereRepeated = exitingPhase(currentRun.typerPhase) {
        for {
          params <- sym.tpe.paramss
          param <- params
        } yield isScalaRepeatedParamType(param.tpe)
      }

      var reversedParts: List[js.Tree] = Nil
      var reversedPartUnderConstruction: List[js.Tree] = Nil

      def closeReversedPartUnderConstruction() = {
        if (!reversedPartUnderConstruction.isEmpty) {
          val part = reversedPartUnderConstruction.reverse
          reversedParts ::= js.JSArrayConstr(part)
          reversedPartUnderConstruction = Nil
        }
      }

      val paramTpes = enteringPhase(currentRun.posterasurePhase) {
        for (param <- sym.tpe.params)
          yield param.tpe
      }

      for (((arg, wasRepeated), tpe) <- (args zip wereRepeated) zip paramTpes) {
        if (wasRepeated) {
          genPrimitiveJSRepeatedParam(arg) match {
            case js.JSArrayConstr(jsArgs) =>
              reversedPartUnderConstruction =
                jsArgs reverse_::: reversedPartUnderConstruction
            case jsArgArray =>
              closeReversedPartUnderConstruction()
              reversedParts ::= jsArgArray
          }
        } else {
          val unboxedArg = genExpr(arg)
          val boxedArg = unboxedArg match {
            case js.UndefinedParam() => unboxedArg
            case _                   => ensureBoxed(unboxedArg, tpe)
          }
          reversedPartUnderConstruction ::= boxedArg
        }
      }
      closeReversedPartUnderConstruction()

      // Find js.UndefinedParam at the end of the argument list. No check is
      // performed whether they may be there, since they will only be placed
      // where default arguments can be anyway
      reversedParts = reversedParts match {
        case Nil => Nil
        case js.JSArrayConstr(params) :: others =>
          val nparams =
            params.reverse.dropWhile(_.isInstanceOf[js.UndefinedParam]).reverse
          js.JSArrayConstr(nparams) :: others
        case parts => parts
      }

      // Find remaining js.UndefinedParam and replace by js.Undefined. This can
      // happen with named arguments or when multiple argument lists are present
      reversedParts = reversedParts map {
        case js.JSArrayConstr(params) =>
          val nparams = params map {
            case js.UndefinedParam() => js.Undefined()
            case param => param
          }
          js.JSArrayConstr(nparams)
        case part => part
      }

      reversedParts match {
        case Nil => js.JSArrayConstr(Nil)
        case List(part) => part
        case _ =>
          val partHead :: partTail = reversedParts.reverse
          js.JSApply(js.JSBracketSelect(
              partHead, js.StringLiteral("concat")), partTail)
      }
    }

    /** Gen JS code for a repeated param of a primitive JS method
     *  In this case `arg` has type Seq[T] for some T, but the result should
     *  have type js.Array[T]. So this method takes care of the conversion.
     *  It is specialized for the shapes of tree generated by the desugaring
     *  of repeated params in Scala, so that these produce a js.JSArrayConstr.
     */
    private def genPrimitiveJSRepeatedParam(arg: Tree): js.Tree = {
      implicit val pos = arg.pos

      // Given a method `def foo(args: T*)`
      arg match {
        // foo(arg1, arg2, ..., argN) where N > 0
        case MaybeAsInstanceOf(WrapArray(
            MaybeAsInstanceOf(ArrayValue(tpt, elems))))
            if elems.forall(e => !isPrimitiveValueType(e.tpe)) => // non-optimal fix to #39
          js.JSArrayConstr(elems map genExpr)

        // foo()
        case Select(_, _) if arg.symbol == NilModule =>
          js.JSArrayConstr(Nil)

        // foo(argSeq:_*)
        case _ =>
          /* Here we fall back to calling js.Any.fromTraversableOnce(seqExpr)
           * to perform the conversion.
           */
          genApplyMethod(
              genLoadModule(JSAnyModule),
              JSAnyModule.moduleClass,
              JSAny_fromTraversableOnce,
              List(genExpr(arg)))
      }
    }

    object MaybeAsInstanceOf {
      def unapply(tree: Tree): Some[Tree] = tree match {
        case Apply(TypeApply(asInstanceOf_? @ Select(base, _), _), _)
        if asInstanceOf_?.symbol == Object_asInstanceOf =>
          Some(base)
        case _ =>
          Some(tree)
      }
    }

    object WrapArray {
      lazy val isWrapArray: Set[Symbol] = Seq(
          nme.wrapRefArray,
          nme.wrapByteArray,
          nme.wrapShortArray,
          nme.wrapCharArray,
          nme.wrapIntArray,
          nme.wrapLongArray,
          nme.wrapFloatArray,
          nme.wrapDoubleArray,
          nme.wrapBooleanArray,
          nme.wrapUnitArray,
          nme.genericWrapArray).map(getMemberMethod(PredefModule, _)).toSet

      def unapply(tree: Apply): Option[Tree] = tree match {
        case Apply(wrapArray_?, List(wrapped))
        if isWrapArray(wrapArray_?.symbol) =>
          Some(wrapped)
        case _ =>
          None
      }
    }

    // Synthesizers for raw JS functions ---------------------------------------

    /** Try and gen and record JS code for an anonymous function class.
     *
     *  Returns true if the class could be rewritten that way, false otherwise.
     *
     *  We make the following assumptions on the form of such classes:
     *  - It is an anonymous function
     *    - Includes being anonymous, final, and having exactly one constructor
     *  - It is not a PartialFunction
     *  - It has no field other than param accessors
     *  - It has exactly one constructor
     *  - It has exactly one non-bridge method apply if it is not specialized,
     *    or a method apply$...$sp and a forwarder apply if it is specialized.
     *  - As a precaution: it is synthetic
     *
     *  From a class looking like this:
     *
     *    final class <anon>(outer, capture1, ..., captureM) extends AbstractionFunctionN[...] {
     *      def apply(param1, ..., paramN) = {
     *        <body>
     *      }
     *    }
     *
     *  we generate:
     *
     *    function(outer, capture1, ..., captureM) {
     *      return function(param1, ..., paramN) {
     *        <body>
     *      }
     *    }
     *
     *  so that, at instantiation point, we can write:
     *
     *    new AnonFunctionN(functionMaker(captured1, ..., capturedM))
     *
     *  Trickier things apply when the function is specialized.
     */
    private def tryGenAndRecordAnonFunctionClass(cd: ClassDef): Boolean = {
      implicit val pos = cd.pos
      val sym = cd.symbol
      assert(sym.isAnonymousFunction,
          s"tryGenAndRecordAnonFunctionClass called with non-anonymous function $cd")

      withScopedVars(
          currentClassInfoBuilder := new ClassInfoBuilder(sym.asClass),
          currentClassSym         := sym
      ) {

        val (functionMakerBase, arity) =
          tryGenAndRecordAnonFunctionClassGeneric(cd) { msg =>
            return false
          }

        val functionMaker = { capturedArgs: List[js.Tree] =>
          JSFunctionToScala(functionMakerBase(capturedArgs), arity)
        }

        translatedAnonFunctions +=
          sym -> (functionMaker, currentClassInfoBuilder.get)

      }
      true
    }

    /** Constructor and extractor object for a tree that converts a JavaScript
     *  function into a Scala function.
     */
    private object JSFunctionToScala {
      private val AnonFunPrefScala =
        "scala.scalajs.runtime.AnonFunction"
      private val AnonFunPrefJS =
        "sjsr_AnonFunction"

      def apply(jsFunction: js.Tree, arity: Int)(
          implicit pos: Position): js.Tree = {
        val clsSym = getRequiredClass(AnonFunPrefScala + arity)
        val ctor = clsSym.tpe.member(nme.CONSTRUCTOR)
        genNew(clsSym, ctor, List(jsFunction))
      }

      def unapply(tree: js.New): Option[(js.Tree, Int)] = tree match {
        case js.New(jstpe.ClassType(wrapperName), _, List(fun))
            if wrapperName.startsWith(AnonFunPrefJS) =>
          val arityStr = wrapperName.substring(AnonFunPrefJS.length)
          try {
            Some((fun, arityStr.toInt))
          } catch {
            case e: NumberFormatException => None
          }

        case _ =>
          None
      }
    }

    /** Gen and record JS code for a raw JS function class.
     *
     *  This is called when emitting a ClassDef that represents an anonymous
     *  class extending `js.FunctionN`. These are generated by the SAM
     *  synthesizer when the target type is a `js.FunctionN`. Since JS
     *  functions are not classes, we deconstruct the ClassDef, then
     *  reconstruct it to be a genuine raw JS function maker.
     *
     *  Compared to `tryGenAndRecordAnonFunctionClass()`, this function must
     *  always succeed, because we really cannot afford keeping them as
     *  anonymous classes. The good news is that it can do so, because the
     *  body of SAM lambdas is hoisted in the enclosing class. Hence, the
     *  apply() method is just a forwarder to calling that hoisted method.
     *
     *  From a class looking like this:
     *
     *    final class <anon>(outer, capture1, ..., captureM) extends js.FunctionN[...] {
     *      def apply(param1, ..., paramN) = {
     *        outer.lambdaImpl(param1, ..., paramN, capture1, ..., captureM)
     *      }
     *    }
     *
     *  we generate:
     *
     *    function(outer, capture1, ..., captureM) {
     *      return function(param1, ..., paramN) {
     *        return outer.lambdaImpl(param1, ..., paramN, capture1, ..., captureM);
     *      }
     *    }
     *
     *  The function maker is recorded in `translatedAnonFunctions` to be
     *  fetched later by the translation for New.
     */
    def genAndRecordRawJSFunctionClass(cd: ClassDef): Unit = {
      val sym = cd.symbol
      assert(isRawJSFunctionDef(sym),
          s"genAndRecordRawJSFunctionClass called with non-JS function $cd")

      withScopedVars(
          currentClassInfoBuilder := new ClassInfoBuilder(sym.asClass),
          currentClassSym         := sym
      ) {

        val (functionMaker, _) =
          tryGenAndRecordAnonFunctionClassGeneric(cd) { msg =>
            abort(s"Could not generate raw function maker for JS function: $msg")
          }

        translatedAnonFunctions +=
          sym -> (functionMaker, currentClassInfoBuilder.get)

      }
    }

    /** Code common to tryGenAndRecordAnonFunctionClass and
     *  genAndRecordRawJSFunctionClass.
     */
    private def tryGenAndRecordAnonFunctionClassGeneric(cd: ClassDef)(
        onFailure: (=> String) => Unit): (List[js.Tree] => js.Tree, Int) = {
      implicit val pos = cd.pos
      val sym = cd.symbol

      // First checks

      if (sym.isSubClass(PartialFunctionClass))
        onFailure(s"Cannot rewrite PartialFunction $cd")
      if (instantiatedAnonFunctions contains sym) {
        // when the ordering we're given is evil (it happens!)
        onFailure(s"Abort function rewrite because it was already instantiated: $cd")
      }

      // First step: find the apply method def, and collect param accessors

      var paramAccessors: List[Symbol] = Nil
      var applyDef: DefDef = null

      def gen(tree: Tree): Unit = {
        tree match {
          case EmptyTree => ()
          case Template(_, _, body) => body foreach gen
          case vd @ ValDef(mods, name, tpt, rhs) =>
            val fsym = vd.symbol
            if (!fsym.isParamAccessor)
              onFailure(s"Found field $fsym which is not a param accessor in anon function $cd")

            if (fsym.isPrivate) {
              paramAccessors ::= fsym
            } else {
              // Uh oh ... an inner something will try to access my fields
              onFailure(s"Found a non-private field $fsym in $cd")
            }
          case dd: DefDef =>
            val ddsym = dd.symbol
            if (ddsym.isClassConstructor) {
              if (!ddsym.isPrimaryConstructor)
                onFailure(s"Non-primary constructor $ddsym in anon function $cd")
            } else {
              val name = dd.name.toString
              if (name == "apply" || (ddsym.isSpecialized && name.startsWith("apply$"))) {
                if ((applyDef eq null) || ddsym.isSpecialized)
                  applyDef = dd
              } else {
                // Found a method we cannot encode in the rewriting
                onFailure(s"Found a non-apply method $ddsym in $cd")
              }
            }
          case _ =>
            onFailure("Illegal tree in gen of genAndRecordAnonFunctionClass(): " + tree)
        }
      }
      gen(cd.impl)
      paramAccessors = paramAccessors.reverse // preserve definition order

      if (applyDef eq null)
        onFailure(s"Did not find any apply method in anon function $cd")

      // Second step: build the list of useful constructor parameters

      val ctorParams = sym.primaryConstructor.tpe.params

      if (paramAccessors.size != ctorParams.size &&
          !(paramAccessors.size == ctorParams.size-1 &&
              ctorParams.head.unexpandedName == newTermName("arg$outer"))) {
        onFailure(
            s"Have param accessors $paramAccessors but "+
            s"ctor params $ctorParams in anon function $cd")
      }

      val hasUnusedOuterCtorParam = paramAccessors.size != ctorParams.size
      val usedCtorParams =
        if (hasUnusedOuterCtorParam) ctorParams.tail
        else ctorParams
      val ctorParamDefs = usedCtorParams map { p =>
        // in the apply method's context
        js.ParamDef(encodeLocalSym(p, freshName)(p.pos), toIRType(p.tpe))(p.pos)
      }

      // Third step: emit the body of the apply method def

      val (applyMethod, methodInfoBuilder) = withScopedVars(
          paramAccessorLocals := (paramAccessors zip ctorParamDefs).toMap
      ) {
        genMethodWithInfoBuilder(applyDef).getOrElse(
          abort(s"Oops, $applyDef did not produce a method"))
      }

      withScopedVars(
          currentMethodInfoBuilder := methodInfoBuilder
      ) {
        // Fourth step: patch the body to unbox parameters and box result

        val js.MethodDef(_, params, _, body) = applyMethod
        val patchedBody = patchFunBodyWithBoxes(applyDef.symbol, params, body)

        // Fifth step: build the function maker

        val functionMakerFun =
          js.Function(ctorParamDefs, jstpe.DynType, {
            js.Return {
              if (JSThisFunctionClasses.exists(sym isSubClass _)) {
                assert(params.nonEmpty, s"Empty param list in ThisFunction: $cd")
                js.Function(params.tail, jstpe.AnyType, js.Block(
                    js.VarDef(params.head.name, params.head.ptpe,
                        mutable = false, js.This()(params.head.ptpe)),
                    patchedBody
                ))
              } else {
                js.Function(params, jstpe.AnyType, patchedBody)
              }
            }
          })

        val functionMaker = { capturedArgs0: List[js.Tree] =>
          val capturedArgs =
            if (hasUnusedOuterCtorParam) capturedArgs0.tail
            else capturedArgs0
          assert(capturedArgs.size == ctorParamDefs.size)
          js.JSApply(functionMakerFun, capturedArgs)
        }

        val arity = params.size

        (functionMaker, arity)
      }
    }

    /** Generate JS code for an anonymous function
     *
     *  Anonymous functions survive until the backend only under
     *  -Ydelambdafy:method
     *  and when they do, their body is always of the form
     *  EnclosingClass.this.someMethod(arg1, ..., argN, capture1, ..., captureM)
     *  where argI are the formal arguments of the lambda, and captureI are
     *  local variables or the enclosing def.
     *
     *  We translate them by instantiating scala.scalajs.runtime.AnonFunctionN
     *  with a JS anonymous function:
     *
     *  new ScalaJS.c.scala_scalajs_runtime_AnonFunctionN().init___xyz(
     *    (function(arg1, ..., argN) {
     *      return this.someMethod(arg1, ..., argN, capture1, ..., captureM)
     *    }).bind(this)
     *  )
     *
     *  (with additional considerations for protecting captures against
     *  mutations)
     *
     *  In addition, input params are unboxed before use, and the result of
     *  someMethod() is boxed back.
     *
     *  Currently, this translation does not take advantage of specialization.
     */
    private def genAnonFunction(originalFunction: Function): js.Tree = {
      implicit val pos = originalFunction.pos
      val Function(paramTrees, Apply(
          targetTree @ Select(receiver, _), actualArgs)) = originalFunction

      val target = targetTree.symbol
      val params = paramTrees.map(_.symbol)

      val isInImplClass = target.owner.isImplClass

      val jsFunction = {
        val jsParams = params map { p =>
          js.ParamDef(encodeLocalSym(p, freshName)(p.pos), toIRType(p.tpe))(p.pos)
        }
        val jsBody = js.Return {
          if (isInImplClass)
            genTraitImplApply(target, actualArgs map genExpr)
          else
            genApplyMethod(js.This()(toIRType(receiver.tpe)),
                receiver.tpe, target, actualArgs map genExpr)
        }
        val patchedBody = patchFunBodyWithBoxes(target, jsParams, jsBody)
        js.Function(jsParams, jstpe.AnyType, patchedBody)
      }

      val boundFunction = {
        if (isInImplClass) {
          jsFunction
        } else {
          js.JSApply(js.JSBracketSelect(
              jsFunction, js.StringLiteral("bind")), List(genExpr(receiver)))
        }
      }

      JSFunctionToScala(boundFunction, params.size)
    }

    private def patchFunBodyWithBoxes(methodSym: Symbol,
        params: List[js.ParamDef], body: js.Tree)(implicit pos: Position): js.Tree = {
      val methodType = enteringPhase(currentRun.posterasurePhase)(methodSym.tpe)

      val unboxParams = for {
        (paramIdent, paramSym) <- params zip methodType.params
        paramTpe = enteringPhase(currentRun.posterasurePhase)(paramSym.tpe)
        paramRef = paramIdent.ref
        unboxedParam = ensureUnboxed(paramRef, paramTpe)
        if unboxedParam ne paramRef
      } yield {
        js.Assign(paramRef, unboxedParam)
      }

      val returnStat = {
        val resultType = methodType.resultType
        body match {
          case js.Return(expr, None) =>
            js.Return(ensureBoxed(expr, resultType))
          case _ =>
            assert(resultType.typeSymbol == UnitClass)
            /* In theory we should return a boxed () value, but that is the
             * undefined value, which is returned automatically in
             * JavaScript when there is no return statement. */
            body
        }
      }

      js.Block(unboxParams :+ returnStat)
    }

    // Utilities ---------------------------------------------------------------

    /** Generate a literal "zero" for the requested type */
    def genZeroOf(tpe: Type)(implicit pos: Position): js.Tree = toTypeKind(tpe) match {
      case UNDEFINED => js.Undefined()
      case BOOL => js.BooleanLiteral(false)
      case LongKind => genLongModuleCall("zero")
      case INT(_) => js.IntLiteral(0)
      case FLOAT(_) => js.DoubleLiteral(0.0)
      case REFERENCE(_) => js.Null()
      case ARRAY(_) => js.Null()
    }

    /** Generate loading of a module value
     *  Can be given either the module symbol, or its module class symbol.
     */
    def genLoadModule(sym0: Symbol)(implicit pos: Position): js.Tree = {
      require(sym0.isModuleOrModuleClass,
          "genLoadModule called with non-module symbol: " + sym0)
      val sym1 = if (sym0.isModule) sym0.moduleClass else sym0
      val sym = // redirect all static methods of String to RuntimeString
        if (sym1 == StringModule) RuntimeStringModule.moduleClass
        else sym1

      val isGlobalScope = sym.tpe.typeSymbol isSubClass JSGlobalScopeClass

      if (isGlobalScope) js.JSGlobal()
      else if (isRawJSType(sym.tpe)) genPrimitiveJSModule(sym)
      else {
        if (!foreignIsImplClass(sym))
          currentMethodInfoBuilder.accessesModule(sym)
        js.LoadModule(jstpe.ClassType(encodeClassFullName(sym)))
      }
    }

    /** Generate a call to scala.scalajs.runtime.RuntimeLong companion */
    private def genLongModuleCall(methodName: String, args: js.Tree*)(implicit pos: Position) = {
      val LongModule = genLoadModule(RuntimeLongModule)
      val encName = scala.reflect.NameTransformer.encode(methodName)
      val method = getMemberMethod(RuntimeLongModule, newTermName(encName))
      genApplyMethod(LongModule, RuntimeLongModule.moduleClass,
          method, args.toList)
    }

    private def genOlLongCall(
        receiver: js.Tree,
        methodName: String,
        args: js.Tree*)(argTypes: Type*)
        (implicit pos: Position): js.Tree = {

      val encName = scala.reflect.NameTransformer.encode(methodName)
      val method = getMemberMethod(
          jsDefinitions.RuntimeLongClass, newTermName(encName))
      assert(method.isOverloaded)

      def checkParams(types: List[Type]) = types.size == argTypes.size &&
        (argTypes zip types).forall { case (t1,t2) => t1 =:= t2 }

      val opt = method.alternatives find { m =>
        checkParams(m.paramss.head.map(_.typeSignature))
      }

      genLongCall(receiver, opt.get, args :_*)
    }

    private def genLongCall(
        receiver: js.Tree,
        methodName: String,
        args: js.Tree*)(implicit pos: Position): js.Tree = {
      val encName = scala.reflect.NameTransformer.encode(methodName)
      val method = getMemberMethod(
          jsDefinitions.RuntimeLongClass, newTermName(encName))
       genLongCall(receiver, method, args :_*)
    }

    private def genLongCall(receiver: js.Tree, method: Symbol, args: js.Tree*)(
        implicit pos: Position): js.Tree =
      genApplyMethod(receiver, RuntimeLongClass, method, args.toList)

    /** Generate access to a static member */
    private def genStaticMember(sym: Symbol)(implicit pos: Position) = {
      /* Actually, there is no static member in Scala.js. If we come here, that
       * is because we found the symbol in a Java-emitted .class in the
       * classpath. But the corresponding implementation in Scala.js will
       * actually be a val in the companion module.
       * So we cheat here. This is a workaround for not having separate
       * compilation yet.
       */
      import scalaPrimitives._
      import jsPrimitives._
      if (isPrimitive(sym)) {
        getPrimitive(sym) match {
          case UNITVAL  => js.Undefined()
          case UNITTYPE => genClassConstant(UnitTpe)
        }
      } else {
        val instance = genLoadModule(sym.owner)
        val method = encodeStaticMemberSym(sym)
        currentMethodInfoBuilder.callsMethod(sym.owner, method)
        js.Apply(instance, method, Nil)(toIRType(sym.tpe))
      }
    }

    /** Generate a Class[_] value (e.g. coming from classOf[T]) */
    private def genClassConstant(tpe: Type)(implicit pos: Position): js.Tree = {
      val (irType, sym) = encodeReferenceType(tpe)
      currentMethodInfoBuilder.accessesClassData(sym)
      js.ClassOf(irType)
    }
  }

  /** Test whether the given type represents a raw JavaScript type
   *
   *  I.e., test whether the type extends scala.js.Any
   */
  def isRawJSType(tpe: Type): Boolean =
    tpe.typeSymbol.annotations.find(_.tpe =:= RawJSTypeAnnot.tpe).isDefined ||
    tpe.typeSymbol == UndefOrClass

  /** Test whether `sym` is the symbol of a raw JS function definition */
  private def isRawJSFunctionDef(sym: Symbol): Boolean =
    sym.isAnonymousClass && AllJSFunctionClasses.exists(sym isSubClass _)

  private def isStringType(tpe: Type): Boolean =
    tpe.typeSymbol == StringClass

  private def isLongType(tpe: Type): Boolean =
    tpe.typeSymbol == LongClass

  private lazy val BoxedBooleanClass = boxedClass(BooleanClass)
  private lazy val BoxedByteClass = boxedClass(ByteClass)
  private lazy val BoxedShortClass = boxedClass(ShortClass)
  private lazy val BoxedIntClass = boxedClass(IntClass)
  private lazy val BoxedLongClass = boxedClass(LongClass)
  private lazy val BoxedFloatClass = boxedClass(FloatClass)
  private lazy val BoxedDoubleClass = boxedClass(DoubleClass)

  private lazy val NumberClass = requiredClass[java.lang.Number]

  private lazy val HijackedNumberClasses =
    Seq(BoxedByteClass, BoxedShortClass, BoxedIntClass, BoxedLongClass,
        BoxedFloatClass, BoxedDoubleClass)
  private lazy val HijackedBoxedClasses =
    Seq(BoxedUnitClass, BoxedBooleanClass) ++ HijackedNumberClasses

  protected lazy val isHijackedBoxedClass: Set[Symbol] =
    HijackedBoxedClasses.toSet

  private lazy val InlineAnnotationClass = requiredClass[scala.inline]

  private def isMaybeJavaScriptException(tpe: Type) =
    JavaScriptExceptionClass isSubClass tpe.typeSymbol

  /** Get JS name of Symbol if it was specified with JSName annotation */
  def jsNameOf(sym: Symbol): String =
    sym.getAnnotation(JSNameAnnotation).flatMap(_.stringArg(0)).getOrElse(
        sym.unexpandedName.decoded)

  def isStaticModule(sym: Symbol): Boolean =
    sym.isModuleClass && !sym.isImplClass && !sym.isLifted
}
