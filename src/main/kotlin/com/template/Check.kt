package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Contract
import net.corda.core.contracts.ContractState
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.serialization.SerializationCustomization
import net.corda.core.transactions.LedgerTransaction
import net.corda.webserver.services.WebServerPluginRegistry
import java.util.function.Function
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import net.corda.core.contracts.*
import net.corda.core.utilities.ProgressTracker
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.flows.*
import java.time.LocalDate
import net.corda.core.transactions.SignedTransaction
import net.corda.contracts.asset.sumCash
import java.time.Instant
import java.util.*

// *****************
// * API Endpoints *
// *****************
@Path("template")
class TemplateApi(val services: CordaRPCOps) {
    // Accessible at /api/template/templateGetEndpoint.
    @GET
    @Path("templateGetEndpoint")
    @Produces(MediaType.APPLICATION_JSON)
    fun templateGetEndpoint(): Response {
        return Response.ok(mapOf("message" to "Template GET endpoint.")).build()
    }
}


// *****************
// * Contract Code *
// *****************
class FutureContract : Contract {
    

    // The legal contract reference - we have to change to actual legal contract reference.
    override val legalContractReference: SecureHash = SecureHash.sha256("https://en.wikipedia.org/wiki/Forwards")

    override fun verify(tx: LedgerTransaction) {

        val command = tx.commands.requireSingleCommand<CommandData>()
        val timeWindow: TimeWindow? = tx.timeWindow
            when (command.value) {
                // see how to group
                is Commands.Move -> {
                    requireThat {
                        val out = tx.outputs.single().data as FutureState
                        "The borrower and lender must be signers." using (command.signers.containsAll(listOf(out.owner.owningKey, out.seller.owningKey)))
                        "the state is propagated" using (tx.outputs.size == 1)
                        // Don't need to check anything else, as if outputs.size == 1 then the output is equal to
                        // the input ignoring the seller field due to the grouping.
                    }
                }

                is Commands.Redeem -> {
                    val input = tx.inputs.single() as FutureState
                    val output = tx.outputs.single()
                    // Redemption of the paper requires movement of on-ledger cash.
                    //val received = tx.outputs.sumCash()
                   // val time = timeWindow?.fromTime ?: throw IllegalArgumentException("Redemptions must have a time-window")
                    requireThat {
                        "the paper must have matured" using (LocalDate.now() >= input.maturityDate)
                        //  "the received amount equals the face value" using (received == input.price)
                        "the paper must be destroyed" using tx.outputs.isEmpty()
                        "the transaction is signed by the seller of the Contract" using (input.owner.owningKey in command.signers)
                    }
                }

                /**is Commands.Issue -> {
                    val output = tx.outputs.single().data as FutureState
                    val time = timeWindow?.untilTime ?: throw IllegalArgumentException("Issuances have a time-window")
                    requireThat {
                        // Don't allow people to issue commercial paper under other entities identities.
                        "output states are issued by a command signer" using (output.issuance.party.owningKey in command.signers)
                        "output values sum to more than the inputs" using (output.quantity > 0)
                        "the maturity date is not in the past" using (time < output.maturityDate)
                        // Don't allow an existing CP state to be replaced by this issuance.
                        // TODO: this has a weird/incorrect assertion string because it doesn't quite match the logic in the clause version.
                        // TODO: Consider how to handle the case of mistaken issuances, or other need to patch.
                        "output values sum to more than the inputs" using inputs.isEmpty()
                    }
                }*/

                is Commands.Issue -> {
                    val output = tx.outputs.single()
                    //val time = timeWindow?.untilTime ?: throw IllegalArgumentException("Issuances have a time-window")
                    requireThat {
                        // Constraints on the shape of the transaction.
                        "No inputs should be consumed when issuing a Future contract." using (tx.inputs.isEmpty())
                        "There should be one output state of type FutureState." using (tx.outputs.size == 1)

                        // Futures_contract-specific constraints.
                        val out = tx.outputs.single().data as FutureState
                        "The Future contract's agreedPrice must be non-negative." using (out.quantity > 0)
                        "The Buyer and the Seller cannot be the same entity." using (out.seller != out.owner)
                        "The Maturity date must be greater than current date." using (out.maturityDate > LocalDate.now())
                        "The commodity in exchange should be positive number." using(out.quantity > 0)

                        // Constraints on the signers.
                        "There must be two signers." using (command.signers.toSet().size==2)
                        "The borrower and lender must be signers." using (command.signers.containsAll(listOf(out.owner.owningKey, out.seller.owningKey)))
                    }
                }

                // TODO: Think about how to evolve contracts over time with new commands.
                else -> throw IllegalArgumentException("Unrecognised command") 
            }
        }  
    }


    // Our Create command.
    interface Commands: CommandData {
        class Move : TypeOnlyCommandData(), Commands
        class Redeem : TypeOnlyCommandData(), Commands
        // We don't need a nonce in the issue command, because the issuance.reference field should already be unique per CP.
        // However, nothing in the platform enforces that uniqueness: it's up to the issuer.
        class Issue : TypeOnlyCommandData(), Commands
        class Create : TypeOnlyCommandData(), Commands
    }



// *********
// * State *
// *********
data class FutureState(val price: Amount<Issued<Currency>>,
               val seller: Party,
               override val owner: AbstractParty,
               val quantity: Int,
               val maturityDate: LocalDate) : ContractState,OwnableState {
    override val contract = FutureContract()
    override val participants get() = listOf(seller, owner)

    override fun withNewOwner(newOwner: AbstractParty) = Pair(Commands.Move(), copy(owner = newOwner))
    /**override fun withOwner(newOwner: AbstractParty): ContractState = copy(owner = newOwner)
    override fun withFaceValue(price: Amount<Issued<Currency>>): ContractState = copy(price = price)
    override fun withMaturityDate(newMaturityDate: LocalDate): ContractState = copy(maturityDate = newMaturityDate)*/


}

// *********
// * Flows *
// *********

// Implemented a very trivial 2 party flow...we must use two party trade flow from corda finance
@InitiatingFlow
@StartableByRPC
class FutureFlow(val futurePrice: Amount<Issued<Currency>>,
              val otherParty: Party,
              val futureDate: LocalDate,
              val futureQuantity: Int) : FlowLogic<Unit>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call() {
        // We retrieve the required identities from the network map.
        val me = serviceHub.myInfo.legalIdentity
        val notary = serviceHub.networkMapCache.getAnyNotary()

        // We create a transaction builder
        val txBuilder = TransactionBuilder(notary = notary)

        // We add the items to the builder.
        val state = FutureState(futurePrice, otherParty, me, futureQuantity, futureDate)
        val cmd = Command(Commands.Move(), listOf(me.owningKey, otherParty.owningKey))
        txBuilder.withItems(state, cmd)

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        // Obtaining the couterparty's signature
        val fullySignedTx = subFlow(CollectSignaturesFlow(signedTx, CollectSignaturesFlow.tracker()))

        // Finalising the transaction.
        subFlow(FinalityFlow(fullySignedTx))
    }
}

@InitiatingFlow
@StartableByRPC
class IssuerFlow(val futurePrice: Amount<Issued<Currency>>,
              val otherParty: Party,
              val futureDate: LocalDate,
              val futureQuantity: Int) : FlowLogic<Unit>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call() {
        // We retrieve the required identities from the network map.
        val me = serviceHub.myInfo.legalIdentity
        val notary = serviceHub.networkMapCache.getAnyNotary()

        // We create a transaction builder
        val txBuilder = TransactionBuilder(notary = notary)

        // We add the items to the builder.
        val state = FutureState(futurePrice, me, otherParty, futureQuantity, futureDate)
        val cmd = Command(Commands.Issue(), listOf(me.owningKey, otherParty.owningKey))
        txBuilder.withItems(state, cmd)

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        // Obtaining the couterparty's signature
        val fullySignedTx = subFlow(CollectSignaturesFlow(signedTx, CollectSignaturesFlow.tracker()))

        // Finalising the transaction.
        subFlow(FinalityFlow(fullySignedTx))
    }
}

@InitiatedBy(FutureFlow::class)
class FutureFlowResponder(val otherParty: Party) : FlowLogic<Unit>(){
	@Suspendable
    override fun call() {
        val signTransactionFlow = object : SignTransactionFlow(otherParty, SignTransactionFlow.tracker()) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an Future transaction." using (output is FutureState)
                val future = output as FutureState
            }
        }

        subFlow(signTransactionFlow)
    }
}

@InitiatedBy(IssuerFlow::class)
class IssuerFlowResponder(val otherParty: Party) : FlowLogic<Unit>(){
	@Suspendable
    override fun call() {
        val signTransactionFlow = object : SignTransactionFlow(otherParty, SignTransactionFlow.tracker()) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an Future transaction." using (output is FutureState)
                val future = output as FutureState
            }
        }

        subFlow(signTransactionFlow)
    }
}

// *******************
// * Plugin Registry *
// *******************
class TemplatePlugin : CordaPluginRegistry() {
    // Whitelisting the required types for serialisation by the Corda node.
    override fun customizeSerialization(custom: SerializationCustomization): Boolean {
        return true
    }
}

class TemplateWebPlugin : WebServerPluginRegistry {
    // A list of classes that expose web JAX-RS REST APIs.
    override val webApis: List<Function<CordaRPCOps, out Any>> = listOf(Function(::TemplateApi))
    //A list of directories in the resources directory that will be served by Jetty under /web.
    // This template's web frontend is accessible at /web/template.
    override val staticServeDirs: Map<String, String> = mapOf(
            // This will serve the templateWeb directory in resources to /web/template
            "template" to javaClass.classLoader.getResource("templateWeb").toExternalForm()
    )
}
