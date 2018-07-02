//Contract Code

class forwardContract: Contract,Obligation {

    val FUTURES_CONTRACT_ID = FuturesContract()

    enum class Role {
        ISSUER,
        CLIENT,
        //TODO()
    }

    data class Terms(
        val asset: Issued<Currency>,
        val maturityDate: Instant,
        val qPrice: Amount<Issued<Currency>>,
        val grade: Char,
        val delviDate: LocalDate,
        val lotSize: Int
    )

    override val legalcontractRefernce = SecureHash.sha256("---------Legal Prose Inserted Here-----------")

    override fun verify(tx: TransactionForContract) = requireThat{
        val command = tx.commands.AnyOfAnyOf(Redeem(),Move(),Issue())) 
        "No more than one input in any case" by ( tx.input <= 1 )
        "One output in every case " by ( tx.output == 1 )
        "Cant sell to yourself" by (otherParty != party)
        "Must be signed by all parties" by ( List<command.signers> == stakeHolders.owningkey)

    }


    fun generateIssue(tx: TransactionBuilder, obligor: AbstractParty, acceptableContract: SecureHash, amount: Amount<Issued<Currency>>, 
                        dueBefore: Instant, beneficiary: AbstractParty, notary: Party, ): {}




    /**class Issue:
    class Agree:
    class Move:
    class Redeem:
    class Settle: */


    interface Clauses{
         /**
         * Common superclass for contract clauses, which defines behaviour on match/no-match, and provides
         * helper functions for the clauses.
         */


    }
}



//Flow

/**Initiates Agreementwith a simple API callfrom a single party without giving access t both parties 
    Change in actual code to offer and accept */

object forwardFlow{

    @InitiatingFlow
    @StartableByRPC
    class Requestor(val deal:DealState) :FlowLogic<SignedTransaction>(){
        companion object{
            object RECIEVED: ProgressTracker.Step("Recieved API call")
            object START: ProgressTracker.Step("Starting FLow") {
                override fun childProgressTracker(): ProgressTracker = TwoPartyDealFlow.Primary.tracker()
            }
        }
    
    // We vend a progress tracker that already knows there's going to be a TwoPartyTradingFlow involved at some
    // point: by setting up the tracker in advance, the user can see what's coming in more detail, instead of being
    // surprised when it appears as a new set of tasks below the current one.

    fun tracker() = ProgressTracker(RECEIVED, DEALING)
    }

    init {
            progressTracker.currentStep = RECEIVED
        }

    @Suspendable
    override fun call(): SignedTransaction{

            require(serviceHub.networkMapCache.notaryNodes.isNotEmpty()) { "No notary nodes registered" }
            val notary = serviceHub.networkMapCache.notaryNodes.first().notaryIdentity

            // need to pick which ever party is not us
            val otherParty = notUs(dealToBeOffered.participants).map { serviceHub.identityService.partyFromAnonymous(it) }.requireNoNulls().single()
            progressTracker.currentStep = DEALING

            val myKey = serviceHub.legalIdentityKey

            val instigator = Instigator(
                    otherParty,
                    AutoOffer(notary, dealToBeOffered),
                    myKey,
                    progressTracker.getChildProgressTracker(DEALING)!!
            )
            val stx = subFlow(instigator)
            return stx
    }

    private fun <T : AbstractParty> notUs(parties: List<T>): List<T> {
        return parties.filter { serviceHub.myInfo.legalIdentity != it }
        }
    }


    @InitiatedBy(Requester::class)
    class AutoOfferAcceptor(otherParty: Party) : Acceptor(otherParty)
}


//State

data class State(
            val issuance: PartyAndReference,
            override val owner: AbstractParty,
            val qPrice: Amount<Issued<Currency>>,
            val maturityDate: Instant

):OwnableState, QueryableState, FuturesContractState, FungibleAsset<Terms<TODO()>> {

    override val contract = CP_PROGRAM_ID

    override val participants: List<AbstractParty>
        get() = listOf(owner)

    abstract val amount: Amount<Issued<Currency>>
    abstract val exitKeys: Collection<PublicKey>








 }

