// #Sireum
/*
 Copyright (c) 2021, Robby, Kansas State University
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are met:

 1. Redistributions of source code must retain the above copyright notice, this
    list of conditions and the following disclaimer.
 2. Redistributions in binary form must reproduce the above copyright notice,
    this list of conditions and the following disclaimer in the documentation
    and/or other materials provided with the distribution.

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.sireum.logika.plugin

import org.sireum._
import org.sireum.lang.{ast => AST}
import org.sireum.lang.symbol.Info
import org.sireum.lang.symbol.Resolver.QName
import org.sireum.lang.tipe.TypeChecker
import org.sireum.logika.{Logika, Smt2, State, StepProofContext}
import org.sireum.logika.Logika.Reporter

object InceptionPlugin {
  @record class Substitutor(substMap: HashMap[String, AST.Typed],
                            context: QName,
                            paramMap: HashMap[String, AST.Exp],
                            reporter: Reporter) extends AST.MTransformer {
    override def preTyped(o: AST.Typed): AST.MTransformer.PreResult[AST.Typed] = {
      o match {
        case o: AST.Typed.TypeVar =>
          substMap.get(o.id) match {
            case Some(t) => return AST.MTransformer.PreResult(F, MSome(t))
            case _ =>
          }
        case _ =>
      }
      return super.preTyped(o)
    }

    override def preExpIdent(o: AST.Exp.Ident): AST.MTransformer.PreResult[AST.Exp] = {
      o.attr.resOpt.get match {
        case res: AST.ResolvedInfo.LocalVar if paramMap.contains(res.id) && res.context == context =>
          return AST.MTransformer.PreResult(F, MSome(paramMap.get(res.id).get))
        case _ =>
      }
      return super.preExpIdent(o)
    }

    override def preExpInvoke(o: AST.Exp.Invoke): AST.MTransformer.PreResult[AST.Exp] = {
      val res: AST.ResolvedInfo.LocalVar = o.ident.attr.resOpt.get match {
        case lv: AST.ResolvedInfo.LocalVar if paramMap.contains(lv.id) && lv.context == context => lv
        case _ => return super.preExpInvoke(o)
      }
      val arg = paramMap.get(res.id).get
      arg match {
        case arg: AST.Exp.Fun =>
          arg.exp match {
            case argExp: AST.Stmt.Expr =>
              var fParamMap = HashMap.empty[String, AST.Exp]
              for (pArg <- ops.ISZOps(arg.params).zip(o.args)) {
                pArg._1.idOpt match {
                  case Some(id) => fParamMap = fParamMap + id.value ~> pArg._2
                  case _ =>
                }
              }
              val subst = Substitutor(substMap, arg.context, fParamMap, Reporter.create)
              val exp = subst.transformExp(argExp.exp)
              reporter.reports(subst.reporter.messages)
              return AST.MTransformer.PreResult(T, exp)
            case _ =>
              reporter.error(arg.posOpt, Logika.kind, "Invalid argument form for inception")
          }
        case AST.Exp.Eta(ref) =>
          ref match {
            case ref: AST.Exp.Ident =>
              return AST.MTransformer.PreResult(T, MSome(o(receiverOpt = None(), ident = ref)))
            case ref: AST.Exp.Select =>
              return AST.MTransformer.PreResult(T, MSome(o(receiverOpt = ref.receiverOpt, ident = AST.Exp.Ident(ref.id, ref.attr))))
          }
        case _ =>
          reporter.error(arg.posOpt, Logika.kind, "Invalid argument form for inception")
      }
      return AST.MTransformer.PreResult(F, MSome(paramMap.get(res.id).get))
    }
  }
}

@datatype class InceptionPlugin extends Plugin {
  @strictpure def canHandle(just: AST.ProofAst.Step.Justification): B = just.isInstanceOf[AST.ProofAst.Step.Inception]

  def handle(logika: Logika,
             smt2: Smt2,
             log: B,
             logDirOpt: Option[String],
             spcMap: HashSMap[Z, StepProofContext],
             state: State,
             step: AST.ProofAst.Step.Regular,
             reporter: Reporter): Plugin.Result = {
    @strictpure def emptyResult: Plugin.Result = Plugin.Result(F, state.nextFresh, ISZ())
    val just = step.just.asInstanceOf[AST.ProofAst.Step.Inception]
    def handleH(invokeIdent: AST.Exp.Ident, args: ISZ[AST.Exp]): Plugin.Result = {
      val res = invokeIdent.attr.resOpt.get.asInstanceOf[AST.ResolvedInfo.Method]
      val mi = logika.th.nameMap.get(res.owner :+ res.id).get.asInstanceOf[Info.Method]
      val posOpt = invokeIdent.posOpt
      val contract: AST.MethodContract.Simple = mi.ast.contract match {
        case c: AST.MethodContract.Simple => c
        case _: AST.MethodContract.Cases =>
          reporter.error(posOpt, Logika.kind, "Could not use method with contract cases")
          return emptyResult
      }
      if (contract.reads.nonEmpty) {
        reporter.error(posOpt, Logika.kind, "Could not use method with non-empty reads clause")
        return emptyResult
      }
      if (contract.modifies.nonEmpty) {
        reporter.error(posOpt, Logika.kind, "Could not use method with non-empty modifies clause")
        return emptyResult
      }

      val smOpt = TypeChecker.unifyFun(Logika.kind, logika.th, posOpt, TypeChecker.TypeRelation.Subtype, res.tpeOpt.get,
        mi.methodType.tpe, reporter)
      val ips = InceptionPlugin.Substitutor(smOpt.get, mi.name,
        HashMap.empty[String, AST.Exp] ++ ops.ISZOps(res.paramNames).zip(args), Reporter.create)
      if (just.witnesses.isEmpty) {
        var provenClaims = HashSet.empty[AST.Exp]
        for (spc <- spcMap.values) {
          spc match {
            case spc: StepProofContext.Regular => provenClaims = provenClaims + spc.exp
            case _ =>
          }
        }
        var ok = T
        for (require <- contract.requires) {
          val req = ips.transformExp(require).getOrElseEager(require)
          if (ips.reporter.messages.isEmpty && !provenClaims.contains(req)) {
            val pos = require.posOpt.get
            reporter.error(posOpt, Logika.kind, st"Could not find a claim satisfying ${(mi.name, ".")}'s pre-condition at [${pos.beginLine}, ${pos.beginColumn}]'".render)
            ok = F
          }
        }
        if (!ok || ips.reporter.messages.nonEmpty) {
          reporter.reports(ips.reporter.messages)
          return emptyResult
        }
      } else {
        var witnesses = HashSet.empty[AST.Exp]
        var ok = T
        for (w <- just.witnesses) {
          spcMap.get(w.value) match {
            case Some(spc: StepProofContext.Regular) => witnesses = witnesses + spc.exp
            case Some(_) =>
              reporter.error(w.posOpt, Logika.kind, s"Cannot use compound proof step #${w.value} as an argument for inception")
              ok = F
            case _ =>
              reporter.error(w.posOpt, Logika.kind, s"Could not find proof step #${w.value}")
              ok = F
          }
        }
        if (!ok) {
          return emptyResult
        }
        val requires: ISZ[AST.Exp] =
          for (require <- contract.requires) yield ips.transformExp(require).getOrElseEager(require)
        if (ips.reporter.messages.nonEmpty) {
          reporter.reports(ips.reporter.messages)
          return emptyResult
        }
        for (i <- 0 until requires.size) {
          if (!witnesses.contains(requires(i))) {
            val pos = contract.requires(i).posOpt.get
            reporter.error(posOpt, Logika.kind, st"Could not find a claim satisfying ${(mi.name, ".")}'s pre-condition at [${pos.beginLine}, ${pos.beginColumn}]".render)
            ok = F
          }
        }
        if (!ok) {
          return emptyResult
        }
      }
      val ensures = HashSet.empty[AST.Exp] ++
        (for (ensure <- contract.ensures) yield ips.transformExp(ensure).getOrElseEager(ensure))
      if (ips.reporter.messages.nonEmpty) {
        reporter.reports(ips.reporter.messages)
        return emptyResult
      }
      if (!ensures.contains(step.claim)) {
        reporter.error(step.claim.posOpt, Logika.kind, st"Could not derive the stated claim from any of ${(mi.name, ".")}'s post-conditions".render)
        return emptyResult
      }
      val (status, nextFresh, claims, claim) = logika.evalRegularStepClaim(smt2, state, step.claim, step.no.posOpt, reporter)
      return Plugin.Result(status, nextFresh, claims :+ claim)
    }
    just match {
      case just: AST.ProofAst.Step.Justification.Incept => return handleH(just.invokeIdent, just.args)
      case just: AST.ProofAst.Step.Justification.InceptNamed => return handleH(just.invokeIdent, just.args)
      case _: AST.ProofAst.Step.Justification.InceptEta =>
        halt("TODO") // TODO
    }
  }
}
