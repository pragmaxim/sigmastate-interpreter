package sigmastate.eval


import sigmastate.Values.{BlockValue, BoolValue, Constant, ConstantNode, EvaluatedCollection, SValue, SigmaPropConstant, ValDef, ValUse, Value}
import org.ergoplatform._

import org.ergoplatform.{Height, Inputs, Outputs, Self}
import sigmastate._
import sigmastate.lang.Terms.ValueOps
import sigmastate.serialization.OpCodes._
import sigmastate.serialization.ConstantStore

import scala.collection.mutable.ArrayBuffer
import SType._
import sigmastate.basics.DLogProtocol.ProveDlog
import sigmastate.basics.ProveDHTuple
import sigmastate.lang.SigmaTyper

trait TreeBuilding extends RuntimeCosting { IR: Evaluation =>
  import Liftables._
  import Context._
  import SigmaProp._
  import Coll._
  import Box._
  import CollBuilder._
  import SigmaDslBuilder._
  import CCostedBuilder._
  import MonoidBuilderInst._
  import BigInt._
  import WArray._
  import WOption._
  import WECPoint._
  import AvlTree._

  private val ContextM = ContextMethods
  private val SigmaM = SigmaPropMethods
  private val CollM = CollMethods
  private val BoxM = BoxMethods
  private val CBM = CollBuilderMethods
  private val SDBM = SigmaDslBuilderMethods
  private val AM = WArrayMethods
  private val OM = WOptionMethods
  private val BIM = BigIntMethods
  private val AvlM = AvlTreeMethods

  /** Describes assignment of valIds for symbols which become ValDefs.
    * Each ValDef in current scope have entry in this map */
  type DefEnv = Map[Sym, (Int, SType)]

  object IsArithOp {
    def unapply(op: EndoBinOp[_]): Option[Byte] = op match {
      case _: NumericPlus[_]    => Some(PlusCode)
      case _: NumericMinus[_]   => Some(MinusCode)
      case _: NumericTimes[_]   => Some(MultiplyCode)
      case _: IntegralDivide[_] => Some(DivisionCode)
      case _: IntegralMod[_]    => Some(ModuloCode)
      case _: OrderingMin[_]    => Some(MinCode)
      case _: OrderingMax[_]    => Some(MaxCode)
      case _ => None
    }
  }

  object IsRelationOp {
    def unapply(op: BinOp[_,_]): Option[(SValue, SValue) => Value[SBoolean.type]] = op match {
      case _: Equals[_]       => Some(builder.mkEQ[SType])
      case _: NotEquals[_]    => Some(builder.mkNEQ[SType])
      case _: OrderingGT[_]   => Some(builder.mkGT[SType])
      case _: OrderingLT[_]   => Some(builder.mkLT[SType])
      case _: OrderingGTEQ[_] => Some(builder.mkGE[SType])
      case _: OrderingLTEQ[_] => Some(builder.mkLE[SType])
      case _ => None
    }
  }

  object IsLogicalBinOp {
    def unapply(op: BinOp[_,_]): Option[(BoolValue, BoolValue) => Value[SBoolean.type]] = op match {
      case And => Some(builder.mkBinAnd)
      case Or  => Some(builder.mkBinOr)
      case _ => None
    }
  }

  object IsLogicalUnOp {
    def unapply(op: UnOp[_,_]): Option[BoolValue => BoolValue] = op match {
      case Not => Some({ v: BoolValue => builder.mkLogicalNot(v) })
      case _ => None
    }
  }

  object IsNumericUnOp {
    def unapply(op: UnOp[_,_]): Option[SValue => SValue] = op match {
      case NumericNegate(_) => Some({ v: SValue => builder.mkNegation(v.asNumValue) })
      case _ => None
    }
  }

  object IsContextProperty {
    def unapply(d: Def[_]): Option[SValue] = d match {
      case ContextM.HEIGHT(_) => Some(Height)
      case ContextM.INPUTS(_) => Some(Inputs)
      case ContextM.OUTPUTS(_) => Some(Outputs)
      case ContextM.SELF(_) => Some(Self)
      case _ => None
    }
  }

  object IsInternalDef {
    def unapply(d: Def[_]): Option[Def[_]] = d match {
      case _: SigmaDslBuilder | _: CollBuilder | _: WSpecialPredefCompanion => Some(d)
      case _ => None
    }
  }

  object IsNonConstantDef {
    def unapply(d: Def[_]): Option[Def[_]] = d match {
      // in case of GroupElement constant (ProveDlog) different constants have different meaning,
      // thus it is ok for them to create ValDef
//      case c: Const[_] if c.elem.isInstanceOf[WECPointElem[_]] => Some(d)
      // to increase effect of constant segregation we need to treat the constants specially
      // and don't create ValDef even if the constant is used more than one time,
      // because two equal constants don't always have the same meaning.
      case _: Const[_] => None
      case _ => Some(d)
    }
  }

  def buildValue(ctx: Rep[Context],
                 mainG: PGraph,
                 env: DefEnv,
                 s: Sym,
                 defId: Int,
                 constantsProcessing: Option[ConstantStore]): SValue = {
    import builder._
    def recurse[T <: SType](s: Sym) = buildValue(ctx, mainG, env, s, defId, constantsProcessing).asValue[T]
    object In { def unapply(s: Sym): Option[SValue] = Some(buildValue(ctx, mainG, env, s, defId, constantsProcessing)) }
    s match {
      case _ if s == ctx => org.ergoplatform.Context
      case _ if env.contains(s) =>
        val (id, tpe) = env(s)
        ValUse(id, tpe) // recursion base
      case Def(Lambda(lam, _, x, y)) =>
        val varId = defId + 1       // arguments are treated as ValDefs and occupy id space
        val env1 = env + (x -> (varId, elemToSType(x.elem)))
        val block = processAstGraph(ctx, mainG, env1, lam, varId + 1, constantsProcessing)
        val rhs = mkFuncValue(Vector((varId, elemToSType(x.elem))), block)
        rhs
      case Def(Apply(fSym, xSym, _)) =>
        val Seq(f, x) = Seq(fSym, xSym).map(recurse)
        builder.mkApply(f, IndexedSeq(x))
      case Def(th @ ThunkDef(root, _)) =>
        val block = processAstGraph(ctx, mainG, env, th, defId, constantsProcessing)
        block
      case Def(Const(x)) =>
        val tpe = elemToSType(s.elem)
        constantsProcessing match {
          case Some(s) =>
            val constant = mkConstant[tpe.type](x.asInstanceOf[tpe.WrappedType], tpe)
              .asInstanceOf[ConstantNode[SType]]
            s.put(constant)(builder)
          case None =>
            mkConstant[tpe.type](x.asInstanceOf[tpe.WrappedType], tpe)
        }
      case Def(wc: LiftedConst[a,_]) =>
        val tpe = elemToSType(s.elem)
        val t = Evaluation.stypeToRType(tpe)
        val tRes = Evaluation.toErgoTreeType(t)
        val v = Evaluation.fromDslData(wc.constValue, tRes)
        mkConstant[tpe.type](v.asInstanceOf[tpe.WrappedType], tpe)

      case Def(IsContextProperty(v)) => v

      case Def(ApplyBinOp(IsArithOp(opCode), xSym, ySym)) =>
        val Seq(x, y) = Seq(xSym, ySym).map(recurse)
        mkArith(x.asNumValue, y.asNumValue, opCode)
      case Def(ApplyBinOp(IsRelationOp(mkNode), xSym, ySym)) =>
        val Seq(x, y) = Seq(xSym, ySym).map(recurse)
        mkNode(x, y)
      case Def(ApplyBinOpLazy(IsLogicalBinOp(mkNode), xSym, ySym)) =>
        val Seq(x, y) = Seq(xSym, ySym).map(recurse)
        mkNode(x, y)
      case Def(ApplyUnOp(IsLogicalUnOp(mkNode), xSym)) =>
        mkNode(recurse(xSym))

      case CBM.fromArray(_, arr @ Def(wc: LiftedConst[a,_])) =>
        val colTpe = elemToSType(s.elem)
        mkConstant[colTpe.type](wc.constValue.asInstanceOf[colTpe.WrappedType], colTpe)
      case CBM.fromItems(_, colSyms, elemT) =>
        val elemTpe = elemToSType(elemT)
        val col = colSyms.map(recurse(_).asValue[elemTpe.type])
        mkConcreteCollection[elemTpe.type](col.toIndexedSeq, elemTpe)
      case CBM.xor(_, colSym1, colSym2) =>
        mkXor(recurse(colSym1), recurse(colSym2))

      case ContextM.getVar(_, Def(Const(id: Byte)), eVar) =>
        val tpe = elemToSType(eVar)
        mkGetVar(id, tpe)

      case BIM.subtract(In(x), In(y)) =>
        mkArith(x.asNumValue, y.asNumValue, MinusCode)
      case BIM.add(In(x), In(y)) =>
        mkArith(x.asNumValue, y.asNumValue, PlusCode)
      case BIM.multiply(In(x), In(y)) =>
        mkArith(x.asNumValue, y.asNumValue, MultiplyCode)
      case BIM.divide(In(x), In(y)) =>
        mkArith(x.asNumValue, y.asNumValue, DivisionCode)
      case BIM.mod(In(x), In(y)) =>
        mkArith(x.asNumValue, y.asNumValue, ModuloCode)
      case BIM.min(In(x), In(y)) =>
        mkArith(x.asNumValue, y.asNumValue, MinCode)
      case BIM.max(In(x), In(y)) =>
        mkArith(x.asNumValue, y.asNumValue, MaxCode)
      case BIM.modQ(In(x)) =>
        mkModQ(x.asBigInt)
      case Def(ApplyBinOp(IsArithOp(opCode), xSym, ySym)) =>
        val Seq(x, y) = Seq(xSym, ySym).map(recurse)
        mkArith(x.asNumValue, y.asNumValue, opCode)
      case Def(ApplyBinOp(IsRelationOp(mkNode), xSym, ySym)) =>
        val Seq(x, y) = Seq(xSym, ySym).map(recurse)
        mkNode(x, y)
      case Def(ApplyBinOpLazy(IsLogicalBinOp(mkNode), xSym, ySym)) =>
        val Seq(x, y) = Seq(xSym, ySym).map(recurse)
        mkNode(x, y)
      case Def(ApplyUnOp(IsLogicalUnOp(mkNode), xSym)) =>
        mkNode(recurse(xSym))
      case Def(ApplyUnOp(IsNumericUnOp(mkNode), xSym)) =>
        mkNode(recurse(xSym))

      case CollM.apply(colSym, In(index)) =>
        val col = recurse(colSym)
        mkByIndex(col, index.asIntValue, None)
      case CollM.length(col) =>
        utxo.SizeOf(recurse(col).asCollection[SType])
      case CollM.exists(colSym, pSym) =>
        val Seq(col, p) = Seq(colSym, pSym).map(recurse)
        mkExists(col.asCollection[SType], p.asFunc)
      case CollM.forall(colSym, pSym) =>
        val Seq(col, p) = Seq(colSym, pSym).map(recurse)
        mkForAll(col.asCollection[SType], p.asFunc)
      case CollM.map(colSym, fSym) =>
        val Seq(col, f) = Seq(colSym, fSym).map(recurse)
        mkMapCollection(col.asCollection[SType], f.asFunc)
      case CollM.getOrElse(colSym, In(index), defValSym) =>
        val col = recurse(colSym)
        val defVal = recurse(defValSym)
        mkByIndex(col, index.asIntValue, Some(defVal))
      case CollM.append(col1Sym, col2Sym) =>
        val Seq(col1, col2) = Seq(col1Sym, col2Sym).map(recurse)
        mkAppend(col1, col2)
      case CollM.slice(colSym, In(from), In(until)) =>
        mkSlice(recurse(colSym), from.asIntValue, until.asIntValue)
      case CollM.foldLeft(colSym, zeroSym, pSym) =>
        val Seq(col, zero, p) = Seq(colSym, zeroSym, pSym).map(recurse)
        mkFold(col, zero, p.asFunc)

      case Def(MethodCall(receiver, m, argsSyms, _)) if receiver.elem.isInstanceOf[CollElem[_, _]] =>
        val colSym = receiver.asInstanceOf[Rep[Coll[Any]]]
        val args = argsSyms.map(_.asInstanceOf[Sym]).map(recurse)
        val col = recurse(colSym).asCollection[SType]
        val colTpe = col.tpe // elemToSType(colSym.elem).asCollection
        val method = SCollection.methods.find(_.name == m.getName).getOrElse(error(s"unknown method Coll.${m.getName}"))
        val typeSubst = (method, args) match {
          case (mth @ SCollection.FlatMapMethod, Seq(f)) =>
            val typeSubst = Map(SCollection.tOV -> f.asFunc.tpe.tRange.asCollection.elemType)
            typeSubst
          case (mth @ SCollection.ZipMethod, Seq(coll: EvaluatedCollection[_, _])) =>
            val typeSubst = Map(SCollection.tOV -> coll.elementType)
            typeSubst
          case (mth, _) => SigmaTyper.emptySubst
        }
        val specMethod = method.withConcreteTypes(typeSubst + (SCollection.tIV -> colTpe.elemType))
        builder.mkMethodCall(col, specMethod, args.toIndexedSeq, Map())

      case BoxM.value(box) =>
        mkExtractAmount(recurse[SBox.type](box))
      case BoxM.propositionBytes(In(box)) =>
        mkExtractScriptBytes(box.asBox)
      case BoxM.getReg(In(box), regId, _) =>
        val tpe = elemToSType(s.elem).asOption
        if (regId.isConst)
          mkExtractRegisterAs(box.asBox, ErgoBox.allRegisters(regId.asValue), tpe)
        else
          builder.mkMethodCall(box, SBox.getRegMethod, IndexedSeq(recurse(regId)),
            Map(SBox.tT -> tpe.elemType))
      case BoxM.creationInfo(In(box)) =>
        mkExtractCreationInfo(box.asBox)
      case BoxM.id(In(box)) =>
        mkExtractId(box.asBox)
      case BoxM.bytes(In(box)) =>
        mkExtractBytes(box.asBox)
      case BoxM.bytesWithoutRef(In(box)) =>
        mkExtractBytesWithNoRef(box.asBox)

      case OM.get(In(optionSym)) =>
        mkOptionGet(optionSym.asValue[SOption[SType]])
      case OM.getOrElse(In(optionSym), In(defVal)) =>
        mkOptionGetOrElse(optionSym.asValue[SOption[SType]], defVal)
      case OM.isDefined(In(optionSym)) =>
        mkOptionIsDefined(optionSym.asValue[SOption[SType]])

      case SigmaM.and_bool_&&(In(prop), In(cond)) =>
        SigmaAnd(Seq(prop.asSigmaProp, mkBoolToSigmaProp(cond.asBoolValue)))
      case SigmaM.or_bool_||(In(prop), In(cond)) =>
        SigmaOr(Seq(prop.asSigmaProp, mkBoolToSigmaProp(cond.asBoolValue)))
      case SigmaM.and_sigma_&&(In(p1), In(p2)) =>
        SigmaAnd(Seq(p1.asSigmaProp, p2.asSigmaProp))
      case SigmaM.or_sigma_||(In(p1), In(p2)) =>
        SigmaOr(Seq(p1.asSigmaProp, p2.asSigmaProp))
      case SigmaM.isValid(In(prop)) =>
        mkSigmaPropIsProven(prop.asSigmaProp)
      case SigmaM.propBytes(In(prop)) =>
        mkSigmaPropBytes(prop.asSigmaProp)

      case Def(AnyZk(_, colSyms, _)) =>
        val col = colSyms.map(recurse(_).asSigmaProp)
        SigmaOr(col)
      case Def(AllZk(_, colSyms, _)) =>
        val col = colSyms.map(recurse(_).asSigmaProp)
        SigmaAnd(col)

      case Def(AnyOf(_, colSyms, _)) =>
        val col = colSyms.map(recurse(_).asBoolValue)
        mkAnyOf(col)
      case Def(AllOf(_, colSyms, _)) =>
        val col = colSyms.map(recurse(_).asBoolValue)
        mkAllOf(col)

      case SDBM.allOf(_,  items) =>
        mkAND(recurse(items))
      case SDBM.anyOf(_,  items) =>
        mkOR(recurse(items))
      case SDBM.atLeast(_, bound, items) =>
        mkAtLeast(recurse(bound), recurse(items))
      case Def(SDBM.xorOf(_,  items)) =>
        mkXorOf(recurse(items))

      case Def(SDBM.sigmaProp(_, In(cond))) =>
        mkBoolToSigmaProp(cond.asBoolValue)
      case SDBM.proveDlog(_, In(g)) =>
        g match {
          case gc: Constant[SGroupElement.type]@unchecked => SigmaPropConstant(ProveDlog(gc.value))
          case _ => mkCreateProveDlog(g.asGroupElement)
        }
      case SDBM.proveDHTuple(_, In(g), In(h), In(u), In(v)) =>
        (g, h, u, v) match {
          case (gc: Constant[SGroupElement.type]@unchecked,
          hc: Constant[SGroupElement.type]@unchecked,
          uc: Constant[SGroupElement.type]@unchecked,
          vc: Constant[SGroupElement.type]@unchecked) =>
            SigmaPropConstant(ProveDHTuple(gc.value, hc.value, uc.value, vc.value))
          case _ =>
            mkCreateProveDHTuple(g.asGroupElement, h.asGroupElement, u.asGroupElement, v.asGroupElement)
        }
      case SDBM.sigmaProp(_, In(cond)) =>
        mkBoolToSigmaProp(cond.asBoolValue)
      case SDBM.byteArrayToBigInt(_, colSym) =>
        mkByteArrayToBigInt(recurse(colSym))
      case SDBM.sha256(_, colSym) =>
        mkCalcSha256(recurse(colSym))
      case SDBM.blake2b256(_, colSym) =>
        mkCalcBlake2b256(recurse(colSym))
      case SDBM.longToByteArray(_, longSym) =>
        mkLongToByteArray(recurse(longSym))
      case SDBM.byteArrayToLong(_, colSym) =>
        mkByteArrayToLong(recurse(colSym))
      case SDBM.decodePoint(_, colSym) =>
        mkDecodePoint(recurse(colSym))

      case Def(IfThenElseLazy(condSym, thenPSym, elsePSym)) =>
        val Seq(cond, thenP, elseP) = Seq(condSym, thenPSym, elsePSym).map(recurse)
        mkIf(cond, thenP, elseP)

      case Def(Tup(In(x), In(y))) =>
        mkTuple(Seq(x, y))  
      case Def(First(pair)) =>
        mkSelectField(recurse(pair), 1)
      case Def(Second(pair)) =>
        mkSelectField(recurse(pair), 2)
      case Def(FieldApply(In(data), IsTupleFN(i))) =>
        mkSelectField(data.asTuple, i)

      case Def(Downcast(inputSym, toSym)) =>
        mkDowncast(recurse(inputSym).asNumValue, elemToSType(toSym).asNumType)
      case Def(Upcast(inputSym, toSym)) =>
        mkUpcast(recurse(inputSym).asNumValue, elemToSType(toSym).asNumType)

      case Def(SimpleStruct(_, fields)) =>
        val items = fields.map { case (n, v) => recurse(v) }
        mkTuple(items)

      // Fallback MethodCall rule: should be the last in this list of cases
      case Def(MethodCall(objSym, m, argSyms, _)) =>
        val obj = recurse[SType](objSym)
        val args = argSyms.collect { case argSym: Sym => recurse[SType](argSym) }
        val method = obj.tpe.asProduct.method(m.getName)
          .getOrElse(error(s"Cannot find method ${m.getName} in object $obj"))
        val specMethod = method.specializeFor(obj.tpe, args.map(_.tpe))
        builder.mkMethodCall(obj, specMethod, args.toIndexedSeq, Map())
//        val method = ((SCollection.methods.find(_.name == m.getName), args) match {
//          case (Some(mth @ SCollection.FlatMapMethod), Seq(f)) =>
//            mth.withConcreteTypes(Map(SCollection.tOV -> f.asFunc.tpe.tRange.asCollection.elemType))
//          case (Some(mth), _) => mth
//          case (None, _) => error(s"unknown method Coll.${m.getName}")
//        }).withConcreteTypes(Map(SCollection.tIV -> colTpe.elemType))

      case Def(d) =>
        !!!(s"Don't know how to buildValue($mainG, $s -> $d, $env, $defId)")
    }
  }

  private def processAstGraph(ctx: Rep[Context],
                              mainG: PGraph,
                              env: DefEnv,
                              subG: AstGraph,
                              defId: Int,
                              constantsProcessing: Option[ConstantStore]): SValue = {
    val valdefs = new ArrayBuffer[ValDef]
    var curId = defId
    var curEnv = env
    for (te <- subG.schedule) {
      val s = te.sym; val d = te.rhs
      if (mainG.hasManyUsagesGlobal(s)
        && IsContextProperty.unapply(d).isEmpty
        && IsInternalDef.unapply(d).isEmpty
        && IsNonConstantDef.unapply(d).nonEmpty)
      {
        val rhs = buildValue(ctx, mainG, curEnv, s, curId, constantsProcessing)
        curId += 1
        val vd = ValDef(curId, Seq(), rhs)
        curEnv = curEnv + (s -> (curId, vd.tpe))  // assign valId to s, so it can be use in ValUse
        valdefs += vd
      }
    }
    val Seq(root) = subG.roots
    val rhs = buildValue(ctx, mainG, curEnv, root, curId, constantsProcessing)
    val res = if (valdefs.nonEmpty) BlockValue(valdefs.toIndexedSeq, rhs) else rhs
    res
  }

  def buildTree[T <: SType](f: Rep[Context => Any],
                            constantsProcessing: Option[ConstantStore] = None): Value[T] = {
    val Def(Lambda(lam,_,_,_)) = f
    val mainG = new PGraph(lam.y)
    val block = processAstGraph(asRep[Context](lam.x), mainG, Map(), mainG, 0, constantsProcessing)
    block.asValue[T]
  }
}
