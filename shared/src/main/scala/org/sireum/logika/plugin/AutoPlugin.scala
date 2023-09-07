// #Sireum
/*
 Copyright (c) 2017-2023, Robby, Kansas State University
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
import org.sireum.logika.{Logika, Smt2, Smt2Query, State, StepProofContext}
import org.sireum.logika.Logika.Reporter

object AutoPlugin {
  @record class MAlgebraChecker(var hasError: B, var msgOpt: Option[String]) extends AST.MTransformer {
    @pure def isUnaryNumeric(kind: AST.ResolvedInfo.BuiltIn.Kind.Type): B = {
      kind match {
        case AST.ResolvedInfo.BuiltIn.Kind.UnaryPlus =>
        case AST.ResolvedInfo.BuiltIn.Kind.UnaryMinus =>
        case _ =>
          return F
      }
      return T
    }

    @pure def isNegation(kind: AST.ResolvedInfo.BuiltIn.Kind.Type): B = {
      kind match {
        case AST.ResolvedInfo.BuiltIn.Kind.UnaryNot =>
        case _ =>
          return F
      }
      return T
    }

    @pure def isScalarArithmetic(kind: AST.ResolvedInfo.BuiltIn.Kind.Type): B = {
      kind match {
        case AST.ResolvedInfo.BuiltIn.Kind.BinaryAdd =>
        case AST.ResolvedInfo.BuiltIn.Kind.BinarySub =>
        case AST.ResolvedInfo.BuiltIn.Kind.BinaryMul =>
        case AST.ResolvedInfo.BuiltIn.Kind.BinaryDiv =>
        case AST.ResolvedInfo.BuiltIn.Kind.BinaryRem =>
        case _ =>
          return F
      }
      return T
    }

    @pure def isRelational(kind: AST.ResolvedInfo.BuiltIn.Kind.Type): B = {
      kind match {
        case AST.ResolvedInfo.BuiltIn.Kind.BinaryEquiv =>
        case AST.ResolvedInfo.BuiltIn.Kind.BinaryEq =>
        case AST.ResolvedInfo.BuiltIn.Kind.BinaryNe =>
        case AST.ResolvedInfo.BuiltIn.Kind.BinaryLt =>
        case AST.ResolvedInfo.BuiltIn.Kind.BinaryLe =>
        case AST.ResolvedInfo.BuiltIn.Kind.BinaryGt =>
        case AST.ResolvedInfo.BuiltIn.Kind.BinaryGe =>
        case AST.ResolvedInfo.BuiltIn.Kind.BinaryFpEq =>
        case AST.ResolvedInfo.BuiltIn.Kind.BinaryFpNe =>
        case _ =>
          return F
      }
      return T
    }

    override def postExp(e: AST.Exp): MOption[AST.Exp] = {
      e match {
        case _: AST.Exp.Quant =>
          fail("Algebra cannot be used with quantifiers")
        case b: AST.Exp.Binary =>
          b.attr.resOpt.get match {
            case AST.ResolvedInfo.BuiltIn(kind) if !(isScalarArithmetic(kind) || isRelational(kind)) =>
              fail(s"Algebra cannot be used with binary op $kind")
            case _ =>
          }
        case u: AST.Exp.Unary =>
          u.attr.resOpt.get match {
            case AST.ResolvedInfo.BuiltIn(kind) if !(isUnaryNumeric(kind) || isNegation(kind)) =>
              fail(s"Algebra cannot be used with unary op $kind")
            case _ =>
          }
        case _ =>
      }
      return super.postExp(e)
    }

    def fail(msg: String): Unit = {
      msgOpt = Some(msg)
      hasError = T
    }
  }

}

@datatype class AutoPlugin extends JustificationPlugin {

  val justificationIds: HashSet[String] = HashSet ++ ISZ[String]("Algebra", "Auto", "Premise")

  val justificationName: ISZ[String] = ISZ("org", "sireum", "justification")

  val name: String = "AutoPlugin"

  @pure override def canHandle(logika: Logika, just: AST.ProofAst.Step.Justification): B = {
    just match {
      case just: AST.ProofAst.Step.Justification.Ref =>
        return justificationIds.contains(just.idString) && just.isOwnedBy(justificationName)
      case _ => return F
    }
  }

  override def handle(logika: Logika,
                      smt2: Smt2,
                      cache: Logika.Cache,
                      spcMap: HashSMap[AST.ProofAst.StepId, StepProofContext],
                      state: State,
                      step: AST.ProofAst.Step.Regular,
                      reporter: Reporter): Plugin.Result = {

    val just = step.just.asInstanceOf[AST.ProofAst.Step.Justification.Ref]

    val id = just.idString
    val posOpt = just.id.posOpt
    val pos = posOpt.get

    def checkAlgebraExp(e: AST.Exp): B = {
      val ac = AutoPlugin.MAlgebraChecker(F, Option.none())
      ac.transformExp(e)
      if (ac.hasError) {
        reporter.error(posOpt, Logika.kind, ac.msgOpt.get)
      }
      return !ac.hasError
    }

    def checkValid(psmt2: Smt2, stat: B, nextFresh: Z, premises: ISZ[State.Claim], conclusion: State.Claim,
                   claims: ISZ[State.Claim]): Plugin.Result = {
      if (id == "Algebra" && !checkAlgebraExp(step.claim)) {
        return Plugin.Result(F, state.nextFresh, ISZ())
      }

      var status = stat
      if (status) {
        val r = psmt2.valid(logika.context.methodName, logika.config, cache, T, s"$id Justification", pos, premises,
          conclusion, reporter)

        def error(msg: String): B = {
          reporter.error(posOpt, Logika.kind, msg)
          return F
        }

        status = r.kind match {
          case Smt2Query.Result.Kind.Unsat => T
          case Smt2Query.Result.Kind.Sat => error(s"Invalid claim of proof step ${step.id}")
          case Smt2Query.Result.Kind.Unknown => error(s"Could not deduce the claim of proof step ${step.id}")
          case Smt2Query.Result.Kind.Timeout => error(s"Timed out when deducing the claim of proof step ${step.id}")
          case Smt2Query.Result.Kind.Error => error(s"Error occurred when deducing the claim of proof step ${step.id}")
        }
      }
      return Plugin.Result(status, nextFresh, claims)
    }

    val provenClaims = HashMap ++ (for (spc <- spcMap.values if spc.isInstanceOf[StepProofContext.Regular]) yield
      (logika.th.normalizeExp(spc.asInstanceOf[StepProofContext.Regular].exp), spc.asInstanceOf[StepProofContext.Regular]))

    if (!just.hasWitness) {
      val claimNorm = logika.th.normalizeExp(step.claim)
      val spcOpt = provenClaims.get(claimNorm)
      spcOpt match {
        case Some(spc) =>
          if (logika.config.detailedInfo) {
            val spcPos = spc.stepNo.posOpt.get
            reporter.inform(step.claim.posOpt.get, Reporter.Info.Kind.Verified,
              st"""Accepted by using ${Plugin.stepNoDesc(F, spc.stepNo)} at [${spcPos.beginLine}, ${spcPos.beginColumn}], i.e.:
                  |
                  |${spc.exp}
                  |""".render)
          }
          return Plugin.Result(T, state.nextFresh, spc.claims)
        case _ =>
          val (pathConditions, _) = org.sireum.logika.Util.claimsToExps(logika.jescmPlugins._4, pos,
            logika.context.methodName, state.claims, logika.th, logika.config.atLinesFresh)
          val normPathConditions = HashSSet.empty[AST.Exp] ++ (for (e <- pathConditions) yield logika.th.normalizeExp(e))
          if (normPathConditions.contains(claimNorm)) {
            if (logika.config.detailedInfo) {
              reporter.inform(pos, Logika.Reporter.Info.Kind.Verified,
                st"""Accepted because the stated claim is in the path conditions:
                    |{
                    |  ${(for (e <- pathConditions) yield e.prettyST, ";\n")}
                    |}""".render)
            }
            return Plugin.Result(T, state.nextFresh, ISZ())
          } else if (id == "Premise") {
            reporter.error(posOpt, Logika.kind,
              st"""The stated claim has not been proven before nor is a premise in:
                  |{
                  |  ${(for (e <- pathConditions) yield e.prettyST, ";\n")}
                  |}""".render)
            return Plugin.Result(F, state.nextFresh, ISZ())
          }
      }
    }

    if (!just.hasWitness) {
      val (stat, nextFresh, premises, conclusion) =
        logika.evalRegularStepClaim(smt2, cache, state, step.claim, step.id.posOpt, reporter)
      return checkValid(smt2, stat, nextFresh, state.claims ++ premises, conclusion, premises :+ conclusion)
    } else {
      val psmt2 = smt2.emptyCache(logika.config)
      val atMap = org.sireum.logika.Util.claimsToExps(logika.jescmPlugins._4, pos, logika.context.methodName,
        state.claims, logika.th, F)._2
      var s1 = state.unconstrainedClaims
      var ok = T
      for (arg <- just.witnesses if ok) {
        val stepNo = arg
        spcMap.get(stepNo) match {
          case Some(spc: StepProofContext.Regular) =>
            val (s2, exp) = logika.rewriteAt(atMap, s1, spc.exp, reporter)
            val ISZ((s3, v)) = logika.evalExp(Logika.Split.Disabled, psmt2, cache, T, s2, exp, reporter)
            val (s4, sym) = logika.value2Sym(s3, v, spc.exp.posOpt.get)
            s1 = s4.addClaim(State.Claim.Prop(T, sym))
          case Some(_) =>
            reporter.error(posOpt, Logika.kind, s"Cannot use compound proof step $stepNo as an argument for $id")
            ok = F
          case _ =>
            reporter.error(posOpt, Logika.kind, s"Could not find proof step $stepNo")
            ok = F
        }
      }
      if (!ok) {
        return Plugin.Result(F, state.nextFresh, ISZ())
      }
      val (s5, exp) = logika.rewriteAt(atMap, s1, step.claim, reporter)
      val (stat, nextFresh, premises, conclusion) =
        logika.evalRegularStepClaim(psmt2, cache, s5, exp, step.id.posOpt, reporter)
      val r = checkValid(psmt2, stat, nextFresh, s1.claims ++ premises, conclusion, premises :+ conclusion)
      smt2.combineWith(psmt2)
      return r
    }
  }

}
