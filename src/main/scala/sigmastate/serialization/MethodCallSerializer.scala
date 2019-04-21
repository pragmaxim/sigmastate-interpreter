package sigmastate.serialization

import sigmastate.Values._
import sigmastate._
import sigmastate.lang.SigmaTyper.STypeSubst
import sigmastate.lang.Terms.{MethodCall, STypeParam}
import sigmastate.utils.{SigmaByteReader, SigmaByteWriter}
import ValueSerializer._
import sigma.util.Extensions._

case class MethodCallSerializer(cons: (Value[SType], SMethod, IndexedSeq[Value[SType]], STypeSubst) => Value[SType])
  extends ValueSerializer[MethodCall] {
  override def opDesc: ValueCompanion = MethodCall

  override def serialize(mc: MethodCall, w: SigmaByteWriter): Unit = {
    w.put(mc.method.objType.typeId, ArgInfo("typeCode", "type of the method (see Table~\\ref{table:predeftypes})"))
    w.put(mc.method.methodId, ArgInfo("methodCode", "a code of the method"))
    w.putValue(mc.obj, ArgInfo("obj", "receiver object of this method call"))
    assert(mc.args.nonEmpty)
    w.putValues(mc.args, ArgInfo("args", "arguments of the method call"))
  }

  override def parse(r: SigmaByteReader): Value[SType] = {
    val typeId = r.getByte()
    val methodId = r.getByte()
    val obj = r.getValue()
    val args = r.getValues()
    val method = SMethod.fromIds(typeId, methodId)
    val specMethod = method.specializeFor(obj.tpe, args.map(_.tpe))
    cons(obj, specMethod, args, Map())
  }
}
