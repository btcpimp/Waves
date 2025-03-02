package com.wavesplatform.state.diffs

import cats.Id
import cats.implicits._
import cats.kernel.Monoid
import com.google.common.base.Throwables
import com.wavesplatform.account.{Address, AddressScheme}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.features.EstimatorProvider._
import com.wavesplatform.features.InvokeScriptSelfPaymentPolicyProvider._
import com.wavesplatform.features.ScriptTransferValidationProvider._
import com.wavesplatform.lang._
import com.wavesplatform.lang.directives.DirectiveSet
import com.wavesplatform.lang.directives.values.{DApp => DAppType, _}
import com.wavesplatform.lang.script.ContractScript.ContractScriptImpl
import com.wavesplatform.lang.script.Script
import com.wavesplatform.lang.v1.ContractLimits
import com.wavesplatform.lang.v1.compiler.Terms._
import com.wavesplatform.lang.v1.evaluator.ctx.impl.waves.WavesContext
import com.wavesplatform.lang.v1.evaluator.ctx.impl.{CryptoContext, PureContext}
import com.wavesplatform.lang.v1.evaluator.{ContractEvaluator, LogItem, ScriptResult}
import com.wavesplatform.lang.v1.traits.Environment
import com.wavesplatform.lang.v1.traits.domain.Tx.ScriptTransfer
import com.wavesplatform.lang.v1.traits.domain.{AttachedPayments, DataItem, Recipient}
import com.wavesplatform.metrics._
import com.wavesplatform.settings.Constants
import com.wavesplatform.state._
import com.wavesplatform.state.diffs.CommonValidation._
import com.wavesplatform.state.diffs.FeeValidation._
import com.wavesplatform.state.reader.CompositeBlockchain
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxValidationError._
import com.wavesplatform.transaction.smart.script.ScriptRunner
import com.wavesplatform.transaction.smart.script.ScriptRunner.TxOrd
import com.wavesplatform.transaction.smart.script.trace.{AssetVerifierTrace, InvokeScriptTrace, TracedResult}
import com.wavesplatform.transaction.smart.{AttachedPaymentExtractor, DApp, InvokeScriptTransaction, WavesEnvironment, buildThisValue}
import com.wavesplatform.transaction.{Asset, Transaction}
import monix.eval.Coeval
import shapeless.Coproduct

import scala.util.{Failure, Success, Try}

object InvokeScriptTransactionDiff {

  private val stats = TxProcessingStats
  import stats.TxTimerExt

  def apply(blockchain: Blockchain)(tx: InvokeScriptTransaction): TracedResult[ValidationError, Diff] = {

    val dAppAddressEi = blockchain.resolveAlias(tx.dAppAddressOrAlias)
    val accScriptEi   = dAppAddressEi.map(blockchain.accountScript)
    val functioncall  = tx.funcCall

    accScriptEi match {
      case Right(Some(sc @ ContractScriptImpl(version, contract))) =>
        val scriptResultE =
          stats.invokedScriptExecution.measureForType(InvokeScriptTransaction.typeId)({
            val invoker = tx.sender.toAddress.bytes
            val result = for {
              directives <- DirectiveSet(version, Account, DAppType).leftMap((_, List.empty[LogItem[Id]]))
              input <- buildThisValue(Coproduct[TxOrd](tx: Transaction), blockchain, directives, None).leftMap((_, List.empty[LogItem[Id]]))
              invocationComplexity <- blockchain.invocationComplexity(sc, blockchain.estimator, tx.funcCallOpt).leftMap((_, List.empty[LogItem[Id]]))
              payments <- AttachedPaymentExtractor.extractPayments(tx, version, blockchain, DApp).leftMap((_, List.empty[LogItem[Id]]))
              invocation = ContractEvaluator.Invocation(
                functioncall,
                Recipient.Address(invoker),
                tx.sender,
                payments,
                tx.dAppAddressOrAlias.bytes,
                tx.id.value,
                tx.fee,
                tx.feeAssetId.compatId
              )
              environment = new WavesEnvironment(
                AddressScheme.current.chainId,
                Coeval.evalOnce(input),
                Coeval(blockchain.height),
                blockchain,
                Coeval(tx.dAppAddressOrAlias.bytes),
                directives
              )
              evaluator <- ContractEvaluator(
                Monoid
                  .combineAll(
                    Seq(
                      PureContext.build(Global, V3).withEnvironment[Environment],
                      CryptoContext.build(Global, V3).withEnvironment[Environment],
                      WavesContext.build(directives)
                    )
                  )
                  .evaluationContext(environment),
                contract,
                invocation,
                version
              )
            } yield (evaluator, invocationComplexity)

            result.leftMap { case (error, log) => ScriptExecutionError(error, log, isAssetScript = false) }
          })
        for {
          scriptResult <- TracedResult(
            scriptResultE,
            List(InvokeScriptTrace(tx.dAppAddressOrAlias, functioncall, scriptResultE.map(_._1._1), scriptResultE.fold(_.log, _._1._2)))
          )
          ((ScriptResult(ds, ps), log), invocationComplexity) = scriptResult

          verifierComplexity = blockchain.accountScriptWithComplexity(tx.sender).map(_._2)

          assetsComplexity =
            (tx.checkedAssets().map(_.id) ++ ps.flatMap(_._3))
              .flatMap(id => blockchain.assetScriptWithComplexity(IssuedAsset(id)))
              .map(_._2)

          dataEntries = ds.map {
            case DataItem.Bool(k, b) => BooleanDataEntry(k, b)
            case DataItem.Str(k, b)  => StringDataEntry(k, b)
            case DataItem.Lng(k, b)  => IntegerDataEntry(k, b)
            case DataItem.Bin(k, b)  => BinaryDataEntry(k, b)
          }

          pmts: List[Map[Address, Map[Option[ByteStr], Long]]] = ps.map {
            case (Recipient.Address(addrBytes), amt, maybeAsset) =>
              Map(Address.fromBytes(addrBytes.arr).explicitGet() -> Map(maybeAsset -> amt))
          }

          feeInfo <- TracedResult(tx.assetFee._1 match {
            case Waves => Right((tx.fee, Map(tx.sender.toAddress -> Portfolio(-tx.fee, LeaseBalance.empty, Map.empty))))
            case asset @ IssuedAsset(_) =>
              for {
                assetInfo <- blockchain
                  .assetDescription(asset)
                  .toRight(GenericError(s"Asset $asset does not exist, cannot be used to pay fees"))
                wavesFee <- Either.cond(
                  assetInfo.sponsorship > 0,
                  Sponsorship.toWaves(tx.fee, assetInfo.sponsorship),
                  GenericError(s"Asset $asset is not sponsored, cannot be used to pay fees")
                )
              } yield {
                (wavesFee,
                 Map(
                   tx.sender.toAddress        -> Portfolio(0, LeaseBalance.empty, Map(asset         -> -tx.fee)),
                   assetInfo.issuer.toAddress -> Portfolio(-wavesFee, LeaseBalance.empty, Map(asset -> tx.fee))
                 ))
              }
          })
          dAppAddress <- TracedResult(dAppAddressEi)
          _ <- TracedResult(checkSelfPayments(dAppAddress, blockchain, tx, ps))
          wavesFee = feeInfo._1
          dataAndPaymentDiff <- TracedResult(payableAndDataPart(blockchain.height, tx, dAppAddress, dataEntries, feeInfo._2))
          _                  <- TracedResult(Either.cond(pmts.flatMap(_.values).flatMap(_.values).forall(_ >= 0), (), NegativeAmount(-42, "")))
          _                  <- TracedResult(validateOverflow(pmts.flatMap(_.values).flatMap(_.values), "Attempt to transfer unavailable funds in contract payment"))
          _ <- TracedResult(
            Either.cond(
              pmts
                .flatMap(_.values)
                .flatMap(_.keys)
                .flatten
                .forall(id => blockchain.assetDescription(IssuedAsset(id)).isDefined),
              (),
              GenericError(s"Unissued assets are not allowed")
            ))
          _ <- TracedResult {
            val totalScriptsInvoked =
              tx.checkedAssets()
                .collect { case asset @ IssuedAsset(_) => asset }
                .count(blockchain.hasAssetScript) +
                ps.count(_._3.fold(false)(id => blockchain.hasAssetScript(IssuedAsset(id)))) +
                (if (blockchain.hasScript(tx.sender)) 1 else 0)
            val minWaves  = totalScriptsInvoked * ScriptExtraFee + FeeConstants(InvokeScriptTransaction.typeId) * FeeUnit
            val txName    = Constants.TransactionNames(InvokeScriptTransaction.typeId)
            val assetName = tx.assetFee._1.fold("WAVES")(_.id.toString)
            Either.cond(
              minWaves <= wavesFee,
              (),
              GenericError(s"Fee in $assetName for $txName (${tx.assetFee._2} in $assetName)" +
                s" with $totalScriptsInvoked total scripts invoked does not exceed minimal value of $minWaves WAVES.")
            )
          }
          scriptsInvoked <- TracedResult {
            val totalScriptsInvoked =
              tx.checkedAssets()
                .collect { case asset @ IssuedAsset(_) => asset }
                .count(blockchain.hasAssetScript) +
                ps.count(_._3.fold(false)(id => blockchain.hasAssetScript(IssuedAsset(id)))) +
                (if (blockchain.hasScript(tx.sender)) { 1 } else { 0 })
            val minWaves  = totalScriptsInvoked * ScriptExtraFee + FeeConstants(InvokeScriptTransaction.typeId) * FeeUnit
            val txName    = Constants.TransactionNames(InvokeScriptTransaction.typeId)
            val assetName = tx.assetFee._1.fold("WAVES")(_.id.toString)
            Either.cond(
              minWaves <= wavesFee,
              totalScriptsInvoked,
              GenericError(s"Fee in $assetName for $txName (${tx.assetFee._2} in $assetName)" +
                s" with $totalScriptsInvoked total scripts invoked does not exceed minimal value of $minWaves WAVES.")
            )
          }

          _ <- foldScriptTransfers(blockchain, tx, dAppAddress)(ps, dataAndPaymentDiff)
        } yield {
          val paymentReceiversMap: Map[Address, Portfolio] = Monoid
            .combineAll(pmts)
            .mapValues(mp => mp.toList.map(x => Portfolio.build(Asset.fromCompatId(x._1), x._2)))
            .mapValues(l => Monoid.combineAll(l))
          val paymentFromContractMap = Map(dAppAddress -> Monoid.combineAll(paymentReceiversMap.values).negate)
          val transfers              = Monoid.combineAll(Seq(paymentReceiversMap, paymentFromContractMap))
          val isr = InvokeScriptResult(data = dataEntries, transfers = paymentReceiversMap.toVector.flatMap {
            case (addr, pf) => InvokeScriptResult.paymentsFromPortfolio(addr, pf)
          })
          val dataAndPaymentDiffTx              = dataAndPaymentDiff.transactions(tx.id())
          val dataAndPaymentDiffTxWithTransfers = dataAndPaymentDiffTx.copy(_2 = dataAndPaymentDiffTx._2 ++ transfers.keys)
          val transferSetDiff                   = Diff.stateOps(portfolios = transfers, scriptResults = Map(tx.id() -> isr))
          dataAndPaymentDiff.copy(
            transactions = dataAndPaymentDiff.transactions.updated(tx.id(), dataAndPaymentDiffTxWithTransfers),
            scriptsRun = scriptsInvoked + 1,
            scriptsComplexity = invocationComplexity + verifierComplexity.getOrElse(0L) + assetsComplexity.sum
          ) |+| transferSetDiff
        }
      case Left(l) => TracedResult(Left(l))
      case _       => TracedResult(Left(GenericError(s"No contract at address ${tx.dAppAddressOrAlias}")))
    }
  }

  private def checkSelfPayments(
    dAppAddress: Address,
    blockchain: Blockchain,
    tx: InvokeScriptTransaction,
    transfers: List[(Recipient.Address, Long, Option[ByteStr])]
): Either[GenericError, Unit] = {
    val ifReject =
      blockchain.disallowSelfPayment &&
      (tx.payments.nonEmpty && tx.sender.toAddress == dAppAddress || transfers.exists(_._1.bytes == dAppAddress.bytes))
    Either.cond(
      !ifReject,
      (),
      GenericError("DApp self-payment is forbidden")
    )
  }

  private def payableAndDataPart(height: Int,
                                 tx: InvokeScriptTransaction,
                                 dAppAddress: Address,
                                 dataEntries: List[DataEntry[_]],
                                 feePart: Map[Address, Portfolio]) = {
    if (dataEntries.length > ContractLimits.MaxWriteSetSize) {
      Left(GenericError(s"WriteSet can't contain more than ${ContractLimits.MaxWriteSetSize} entries"))
    } else if (dataEntries.exists(_.key.getBytes("UTF-8").length > ContractLimits.MaxKeySizeInBytes)) {
      Left(GenericError(s"Key size must be less than ${ContractLimits.MaxKeySizeInBytes}"))
    } else {
      val totalDataBytes = dataEntries.map(_.toBytes.length).sum

      val payablePart: Map[Address, Portfolio] = tx.payments
        .map {
          case InvokeScriptTransaction.Payment(amt, assetId) =>
            assetId match {
              case asset @ IssuedAsset(_) =>
                Map(tx.sender.toAddress -> Portfolio(0, LeaseBalance.empty, Map(asset -> -amt))).combine(
                  Map(dAppAddress -> Portfolio(0, LeaseBalance.empty, Map(asset -> amt)))
                )
              case Waves =>
                Map(tx.sender.toAddress -> Portfolio(-amt, LeaseBalance.empty, Map.empty))
                  .combine(Map(dAppAddress -> Portfolio(amt, LeaseBalance.empty, Map.empty)))
            }
        }
        .foldLeft(Map[Address, Portfolio]())(_ combine _)

      if (totalDataBytes <= ContractLimits.MaxWriteSetSizeInBytes) {
        Right(
          Diff(tx = tx,
               portfolios = feePart combine payablePart,
               accountData = Map(dAppAddress -> AccountDataInfo(dataEntries.map(d => d.key -> d).toMap)))
        )
      } else
        Left(GenericError(s"WriteSet size can't exceed ${ContractLimits.MaxWriteSetSizeInBytes} bytes, actual: $totalDataBytes bytes"))
    }
  }

  private def foldScriptTransfers(blockchain: Blockchain, tx: InvokeScriptTransaction, dAppAddress: Address)(
      ps: List[(Recipient.Address, Long, Option[ByteStr])],
      dataDiff: Diff): TracedResult[ValidationError, Diff] = {
    if (ps.length <= ContractLimits.MaxTransferPaymentAmount) {
      val foldResult = ps.foldLeft(TracedResult(dataDiff.asRight[ValidationError])) { (tracedDiffAcc, payment) =>
        val (addressRepr, amount, asset) = payment
        val address                      = Address.fromBytes(addressRepr.bytes.arr).explicitGet()
        val tracedDiff: TracedResult[ValidationError, Diff] = Asset.fromCompatId(asset) match {
          case Waves =>
            Diff
              .stateOps(
                portfolios = Map(
                  address     -> Portfolio(amount, LeaseBalance.empty, Map.empty),
                  dAppAddress -> Portfolio(-amount, LeaseBalance.empty, Map.empty)
                )
              )
              .asRight[ValidationError]
          case a @ IssuedAsset(id) =>
            val nextDiff = Diff.stateOps(
              portfolios = Map(
                address     -> Portfolio(0, LeaseBalance.empty, Map(a -> amount)),
                dAppAddress -> Portfolio(0, LeaseBalance.empty, Map(a -> -amount))
              ))
            blockchain.assetScript(a) match {
              case None =>
                nextDiff.asRight[ValidationError]
              case Some(script) =>
                val assetValidationDiff = tracedDiffAcc.resultE.flatMap(
                  d => validateScriptTransferWithSmartAssetScript(blockchain, tx)(d, addressRepr, amount, a.id, nextDiff, script)
                )
                val errorOpt = assetValidationDiff.fold(Some(_), _ => None)
                TracedResult(
                  assetValidationDiff,
                  List(AssetVerifierTrace(id, errorOpt))
                )
            }
        }
        tracedDiffAcc |+| tracedDiff
      }
      TracedResult(foldResult.resultE.map(_ => Diff.stateOps()), foldResult.trace)
    } else {
      Left(GenericError(s"Too many ScriptTransfers: max: ${ContractLimits.MaxTransferPaymentAmount}, actual: ${ps.length}"))
    }
  }

  private def validateScriptTransferWithSmartAssetScript(blockchain: Blockchain, tx: InvokeScriptTransaction)(
      totalDiff: Diff,
      addressRepr: Recipient.Address,
      amount: Long,
      assetId: ByteStr,
      nextDiff: Diff,
      script: Script): Either[ValidationError, Diff] = {
    Try {
      ScriptRunner(
        Coproduct[TxOrd](
          ScriptTransfer(
            Some(assetId),
            Recipient.Address(tx.dAppAddressOrAlias.bytes),
            Recipient.Address(addressRepr.bytes),
            amount,
            tx.timestamp,
            tx.id()
          )),
        CompositeBlockchain(blockchain, Some(totalDiff)),
        script,
        isAssetScript = true,
        scriptContainerAddress = if (blockchain.passCorrectAssetId) assetId else tx.dAppAddressOrAlias.bytes
      ) match {
        case (log, Left(error))  => Left(ScriptExecutionError(error, log, isAssetScript = true))
        case (log, Right(FALSE)) => Left(TransactionNotAllowedByScript(log, isAssetScript = true))
        case (_, Right(TRUE))    => Right(nextDiff)
        case (log, Right(x))     => Left(ScriptExecutionError(s"Script returned not a boolean result, but $x", log, isAssetScript = true))
      }
    } match {
      case Failure(e) =>
        Left(ScriptExecutionError(s"Uncaught execution error: ${Throwables.getStackTraceAsString(e)}", List.empty, isAssetScript = true))
      case Success(s) => s
    }
  }
}
