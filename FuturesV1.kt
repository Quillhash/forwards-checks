package net.corda.contracts

import co.paralleluniverse.fibers.Suspendable
import net.corda.contracts.asset.sumCashBy
import net.corda.contracts.clause.AbstractIssue
import net.corda.core.contracts.*
import net.corda.core.contracts.clauses.AnyOf
import net.corda.core.contracts.clauses.Clause
import net.corda.core.contracts.clauses.GroupClauseVerifier
import net.corda.core.contracts.clauses.verifyClause
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.toBase58String
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.node.services.VaultService
import net.corda.core.random63BitValue
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.Emoji
import net.corda.schemas.FuturesContractSchemaV1
import java.time.Instant
import java.util.*

/**
 * This is an ultra-trivial implementation of Futures contract
 *
 * This contract is not intended to realistically model a contract. It is here only to act as a next step up above cash in
 * the prototyping phase. It is thus very incomplete.
*/

val CP_PROGRAM_ID = ForwardsContract()

// TODO: Generalise the notion of an owned instrument into a superclass/supercontract. Consider composition vs inheritance.
class ForwardsContract : Contract {
    // TODO: should reference the content of the legal agreement, not its URI
    override val legalContractReference: SecureHash = SecureHash.sha256("https://en.wikipedia.org/wiki/Forwards")

    data class Terms(
            val asset: Issued<Currency>,
            val maturityDate: LocalDate,
            val symbol: Char,
            val grade: Char,
            val qPrice: Amount,
            val dDate: Localdate,
            val lastDate: Localdate
    )

    override fun verify(tx: TransactionForContract) = verifyClause(tx, Clauses.Group(), tx.commands.select<Commands>())

    data class State(
            val issuance: PartyAndReference,
            override val owner: AbstractParty,
            val faceValue: Amount<Issued<Currency>>,
            val maturityDate: Instant
    ) : OwnableState, QueryableState, FuturesContractState {
        override val contract = CP_PROGRAM_ID
        override val participants: List<AbstractParty>
            get() = listOf(owner)

        val token: Issued<Terms>
            get() = Issued(issuance, Terms(faceValue.token, maturityDate))

        override fun withNewOwner(newOwner: AbstractParty) = Pair(Commands.Move(), copy(owner = newOwner))
        override fun toString() = "${Emoji.newspaper}FuturesContract(of $faceValue redeemable on $maturityDate by '$issuance', owned by $owner)"

        // Although kotlin is smart enough not to need these, as we are using the FuturesContractState, we need to declare them explicitly for use later,
        override fun withOwner(newOwner: AbstractParty): FuturesContractState = copy(owner = newOwner)

        override fun withFaceValue(newFaceValue: Amount<Issued<Currency>>): FuturesContractState = copy(faceValue = newFaceValue)
        override fun withMaturityDate(newMaturityDate: Instant): FuturesContractState = copy(maturityDate = newMaturityDate)

        // DOCSTART VaultIndexedQueryCriteria
        /** Object Relational Mapping support. */
        override fun supportedSchemas(): Iterable<MappedSchema> = listOf()
        /** Additional used schemas would be added here (eg. FuturesContractV2, ...) */

        /** Object Relational Mapping support. */
        override fun generateMappedObject(schema: MappedSchema): PersistentState {
            return when (schema) {
                )
                /** Additional schema mappings would be added here (eg. FuturesContractV2, ...) */
                else -> throw IllegalArgumentException("Unrecognised schema $schema")
            }
        }
        // DOCEND VaultIndexedQueryCriteria
    }

    interface Clauses {
        class Group : GroupClauseVerifier<State, Commands, Issued<Terms>>(
                AnyOf(
                        Redeem(),
                        Move(),
                        Issue())) {
            override fun groupStates(tx: TransactionForContract): List<TransactionForContract.InOutGroup<State, Issued<Terms>>>
                    = tx.groupStates<State, Issued<Terms>> { it.token }
        }

        class Issue : AbstractIssue<State, Commands, Terms>(
                { map { Amount(it.faceValue.quantity, it.token) }.sumOrThrow() },
                { token -> map { Amount(it.faceValue.quantity, it.token) }.sumOrZero(token) }) {
            override val requiredCommands: Set<Class<out CommandData>> = setOf(Commands.Issue::class.java)

            override fun verify(tx: TransactionForContract,
                                inputs: List<State>,
                                outputs: List<State>,
                                commands: List<AuthenticatedObject<Commands>>,
                                groupingKey: Issued<Terms>?): Set<Commands> {
                val consumedCommands = super.verify(tx, inputs, outputs, commands, groupingKey)
                commands.requireSingleCommand<Commands.Issue>()
                val timeWindow = tx.timeWindow
                val time = timeWindow?.untilTime ?: throw IllegalArgumentException("Issuances must have a time-window")

                require(outputs.all { time < it.maturityDate }) { "maturity date is not in the past" }

                return consumedCommands
            }
        }

        class Move : Clause<State, Commands, Issued<Terms>>() {
            override val requiredCommands: Set<Class<out CommandData>> = setOf(Commands.Move::class.java)

            override fun verify(tx: TransactionForContract,
                                inputs: List<State>,
                                outputs: List<State>,
                                commands: List<AuthenticatedObject<Commands>>,
                                groupingKey: Issued<Terms>?): Set<Commands> {
                val command = commands.requireSingleCommand<Commands.Move>()
                val input = inputs.single()
                requireThat {
                    "the transaction is signed by the owner of the CP" using (input.owner.owningKey in command.signers)
                    "the state is propagated" using (outputs.size == 1)
                    // Don't need to check anything else, as if outputs.size == 1 then the output is equal to
                    // the input ignoring the owner field due to the grouping.
                }
                return setOf(command.value)
            }
        }

        class Redeem : Clause<State, Commands, Issued<Terms>>() {
            override val requiredCommands: Set<Class<out CommandData>> = setOf(Commands.Redeem::class.java)

            override fun verify(tx: TransactionForContract,
                                inputs: List<State>,
                                outputs: List<State>,
                                commands: List<AuthenticatedObject<Commands>>,
                                groupingKey: Issued<Terms>?): Set<Commands> {
                // TODO: This should filter commands down to those with compatible subjects (underlying product and maturity date)
                // before requiring a single command
                val command = commands.requireSingleCommand<Commands.Redeem>()
                val timeWindow = tx.timeWindow

                val input = inputs.single()
                val received = tx.outputs.sumCashBy(input.owner)
                val time = timeWindow?.fromTime ?: throw IllegalArgumentException("Redemptions must have a time-window")
                requireThat {
                    "the paper must have matured" using (time >= input.maturityDate)
                    "the received amount equals the face value" using (received == input.faceValue)
                    "the paper must be destroyed" using outputs.isEmpty()
                    "the transaction is signed by the owner of the CP" using (input.owner.owningKey in command.signers)
                }

                return setOf(command.value)
            }

        }
    }

    interface Commands : CommandData {
        data class Move(override val contractHash: SecureHash? = null) : FungibleAsset.Commands.Move, Commands
        class Redeem : TypeOnlyCommandData(), Commands
        data class Issue(override val nonce: Long = random63BitValue()) : IssueCommand, Commands
    }

    /**
     * Returns a transaction that issues Futures contract, owned by the issuing parties key. Does not update
     * an existing transaction because you aren't able to issue multiple pieces of CP in a single transaction
     * at the moment: this restriction is not fundamental and may be lifted later.
     */
    fun generateIssue(issuance: PartyAndReference, faceValue: Amount<Issued<Currency>>, maturityDate: LocalDate, notary: Party): TransactionBuilder {
        val state = TransactionState(State(issuance, issuance.party, faceValue, maturityDate), notary)
        return TransactionType.General.Builder(notary = notary).withItems(state, Command(Commands.Issue(), issuance.party.owningKey))
    }

    /**
     * Updates the given partial transaction with an input/output/command to reassign ownership of the paper.
     */
    fun generateMove(tx: TransactionBuilder, paper: StateAndRef<State>, newOwner: AbstractParty) {
        tx.addInputState(paper)
        tx.addOutputState(TransactionState(paper.state.data.copy(owner = newOwner), paper.state.notary))
        tx.addCommand(Commands.Move(), paper.state.data.owner.owningKey)
    }

    /**
     * Intended to be called by the issuer of some Futures contract, when an owner has notified us that they wish
     * to redeem the paper. We must therefore send enough money to the key that owns the paper to satisfy the face
     * value, and then ensure the paper is removed from the ledger.
     *
     * @throws InsufficientBalanceException if the vault doesn't contain enough money to pay the redeemer.
     */
    @Throws(InsufficientBalanceException::class)
    @Suspendable
    fun generateRedeem(tx: TransactionBuilder, paper: StateAndRef<State>, vault: VaultService) {
        // Add the cash movement using the states in our vault.
        val amount = paper.state.data.faceValue.let { amount -> Amount(amount.quantity, amount.token.product) }
        vault.generateSpend(tx, amount, paper.state.data.owner)
        tx.addInputState(paper)
        tx.addCommand(FuturesContract.Commands.Redeem(), paper.state.data.owner.owningKey)
    }
}

infix fun FuturesContract.State.`owned by`(owner: AbstractParty) = copy(owner = owner)
infix fun FuturesContract.State.`with notary`(notary: Party) = TransactionState(this, notary)
infix fun FuturesContractState.`owned by`(newOwner: AbstractParty) = withOwner(newOwner)


