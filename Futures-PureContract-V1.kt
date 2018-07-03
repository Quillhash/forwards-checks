//Contract Code

class forwardContract: Contract,Obligation {

    val FUTURES_CONTRACT_ID = FuturesContract()

    enum class Role {

    }

    data class Terms(
        val issuance: PartyAndReference,/** need ? */
        override val owner: AbstractParty,/** need ? */
        val asset: Issued<Currency>,
        val dDate: LocalDate, 
        val grade: Char,
        val lotSize: Int,
        val maturityDate: Instant 
    ) //Type :BusinessCalender? for dDate,  maturity Date ???

    @CordaSerializable
    abstract class FuturesContract(
        val issuance: PartyAndReference,/** need ? */
        override val owner: AbstractParty,/** need ? */
        val asset: Issued<Currency>,
        val dDate: LocalDate,
        val grade: Char,
        val lotSize: Int,
        val maturityDate: Instant 
        ){

        companion object{
            val CSVHeader = "Asset, Delivery Date,Days Left, Quality, Lot Size, Maturity Date"
                }

        val days: Int get() = calculateDaysBetween(maturityDate , /** TODO()time.now()*/)

        open fun asCSV() = "$asset,$dDate,$days,$grade,$lotSize,$maturityDate"

        }

    override fun hashcode() = Objects.hash(asset, dDate, grade, lotSize, maturityDate)

    override fun verify(tx: LedgerTransaction) = verifyClause(tx, /**AllOf(Clauses.TimeWindow(), Clauses.Group())*/, tx.commands.select<Commands>())

    interface Clauses{
        /**
         * Common superclass for IRS contract clauses, which defines behaviour on match/no-match, and provides
         * helper functions for the clauses.
         */

        abstract class AbstractForwardsClause : Clause<State, Commands, UniqueIdentifier>() {
            // These functions may make more sense to use for basket types, but for now let's leave them here
            fun checkValidity(legs: List<Terms>) {
                requireThat {
                    "Maturity date is before Delivery date" using legs.all { it.maturityDate < it.dDate }
                    "Maturity date is after today" using legs.all { it.maturityDate > date.now() }
                }
            }

            fun checkQuantity(legs: List<Terms>) {
                requireThat {
                    "The lot size is non zero" using legs.any { it.lotSize > 0 }
                }
            }
        }

        class TimeWindow : Clause<ContractState, Commands, Unit>() {
            override fun verify(tx: LedgerTransaction,
                                inputs: List<ContractState>,
                                outputs: List<ContractState>,
                                commands: List<AuthenticatedObject<Commands>>,
                                groupingKey: Unit?): Set<Commands> {
                requireNotNull(tx.timeWindow) { "must be have a time-window)" }
                // We return an empty set because we don't process any commands
                return emptySet()
            }
        }

        class Issue : AbstractIssue<State, Commands, Terms>(
                { map { Amount(it.lotSize, it.asset) }.sumOrThrow() },
                { token -> map { Amount(it.lotSize, it.asset) }.sumOrZero(asset) }) {
            override val requiredCommands: Set<Class<out CommandData>> = setOf(Commands.Issue::class.java)

            override fun verify(tx: TransactionForContract,
                                inputs: List<State>,
                                outputs: List<State>,
                                commands: List<AuthenticatedObject<Commands>>,
                                groupingKey: Issued<Terms>?): Set<Commands> {
                val consumedCommands = super.verify(tx, inputs, outputs, commands, groupingKey)
                commands.requireSingleCommand<Commands.Issue>()
                val timeWindow = tx.timeWindow
                val time = timeWindow?.untilTime ?: throw IllegalArgumentException("Issuances must have a time-window") // TODO() Is this Required?
                require(outputs.all { time < it.maturityDate }) { "Maturity Date is not in the past" }
                require(outputs.all { time < it.dDate }) {"Delivery Date is not in the past"}

                return consumedCommands
            }
        }

        class Agree: AbstractForwardsClause() {
            override val requiredCommands: Set<Class<out CommandData>> = setOf(Commands.Agree::class.java)

            override fun verify(tx: LedgerTransaction,
                                inputs: List<State>,
                                outputs: List<State>,
                                commands: List<AuthenticatedObject<Commands>>,
                                groupingKey: UniqueIdentifier?): Set<Commands> {
                val command = tx.commands.requireSingleCommand<Commands.Agree>()
                val future = outputs.filterIsInstance<State>().single()
                
                requireThat{
                    "There are no in states for an agreement" using inputs.isEmpty()
                    "Asset is not zero" using (/**TODO() != NULL*/)
                    "The lot size must be positive" using (irs.fixedLeg.fixedRate.isPositive())

                    "The maturity date is in the future" using (future.Terms.maturityDate > time.now())
                    "The effective date is before the termination date for the floating leg" using (future.Terms.dDate > time.now())

                    //"The " using checkRates(listOf())
                    //"The schedules are valid" using checkSchedules(listOf(irs.fixedLeg, irs.floatingLeg))
                    // Further Tests Check
                    }
                    checkLegAmounts(listOf(irs.fixedLeg, irs.floatingLeg))
                    checkLegDates(listOf(irs.fixedLeg, irs.floatingLeg))
                    
                    return setOf(command.value)
            }
        }



        class Pay : AbstractForwardsClause() {
            override val requiredCommands: Set<Class<out CommandData>> = setOf(Commands.Pay::class.java)

            override fun verify(tx: LedgerTransaction,
                                inputs: List<State>,
                                outputs: List<State>,
                                commands: List<AuthenticatedObject<Commands>>,
                                groupingKey: UniqueIdentifier?): Set<Commands> {
                val command = tx.commands.requireSingleCommand<Commands.Pay>()
                requireThat {
                    "Payments not supported / verifiable yet" using false
                }
                return setOf(command.value)
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
                    "the transaction is signed by the owner of the Contract" using (input.owner.owningKey in command.signers)
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
                    "the futures must have matured" using (time >= input.maturityDate)
                    "the cleared amount equals the lot size" using (received == input.lotSize)
                    "the futures must be destroyed" using outputs.isEmpty()
                    "the transaction is signed by the owner of the CP" using (input.owner.owningKey in command.signers)
                }

                return setOf(command.value)
            }

        }
            //TODO() Add more functionality

    }

    interface Commands : CommandData {
        data class Issue(override val nonce: Long = random63BitValue()) : IssueCommand, Commands  // Futures to be Issued trusted party for bank //bank of corda issues for now
        class Agree : TypeOnlyCommandData(), Commands  // Both sides agree to trade
        class Pay : TypeOnlyCommandData(), Commands    // Not implemented just yet //See how to
        data class Move(override val contractHash: SecureHash? = null) : FungibleAsset.Commands.Move, Commands
        class Redeem : TypeOnlyCommandData(), Commands // Trade has matured; Pay Dues.
    }

//-------------------------------------------------------------Needs updating-||---------------------------------------------------------------------------------
//-------------------------------------------------------------Needs updating-||---------------------------------------------------------------------------------
//-------------------------------------------------------------Needs updating-||---------------------------------------------------------------------------------
//-------------------------------------------------------------Needs updating-\/---------------------------------------------------------------------------------


    /**
     * Returns a transaction that issues the contract, owned by the issuing parties key. Does not update
     * an existing transaction because you aren't able to issue multiple pieces in a single transaction
     * at the moment: this restriction is not fundamental and may be lifted later.
     */
    fun generateIssue(issuance: PartyAndReference, asset: Issued<Currency>, dDate: LocalDate, grade: Char, lotSize: Int, maturityDate: Instant, notary: Party): TransactionBuilder {
        val state = TransactionState(State(issuance, issuance.party, asset, dDate, grade, lotSize, maturityDate), notary)
        return TransactionType.General.Builder(notary = notary).withItems(state, Command(Commands.Issue(), issuance.party.owningKey))
    }

    /**
     * Updates the given partial transaction with an input/output/command to reassign ownership of the futures.
     */
    fun generateMove(tx: TransactionBuilder, futures: StateAndRef<State>, newOwner: AbstractParty) {
        tx.addInputState(futures)
        tx.addOutputState(TransactionState(futures.state.data.copy(owner = newOwner), futures.state.notary))
        tx.addCommand(Commands.Move(), futures.state.data.owner.owningKey)
        //Change futures name to something and see for additional changes
    }

    // Needs work (a f lot)
    fun generateAgreement(owner: PartyAndReference, newOwner: PartyAndReference,val asset: Issued<Currency>,val dDate: LocalDate, 
                          val grade: Char, val lotSize: Int, val maturityDate: Instant, notary: Party): TransactionBuilder {

        //val state = State(fixedLeg, floatingLeg, newCalculation, common)
        return TransactionType.General.Builder(notary = notary).withItems(state, Command(Commands.Agree(), listOf(state.Owner.owningKey, state.newOwner.owningKey)))
    }

    /**
     * Intended to be called by the issuer of some Contract, when an owner has notified us that they wish
     * to redeem the futures. We must therefore send enough money to the key that owns the futures to satisfy the face
     * value, and then ensure the futures is removed from the ledger.
     *
     * @throws InsufficientBalanceException if the vault doesn't contain enough money to pay the redeemer.
     */

    @Throws(InsufficientBalanceException::class)
    @Suspendable
    fun generateRedeem(tx: TransactionBuilder, futures: StateAndRef<State>, vault: VaultService) {
        // Add the cash movement using the states in our vault.
        val amount = futures.state.data.lotSize.let { amount -> Amount(amount.lotSize, amount.asset) }
        vault.generateSpend(tx, amount, futures.state.data.owner)
        tx.addInputState(futures)
        tx.addCommand(FuturesContract.Commands.Redeem(), futures.state.data.owner.owningKey)

        // SEE ALL CHANGES 
    }






















}





