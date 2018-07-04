//State

data class State(
        val issuance: PartyAndReference,
        override val owner: AbstractParty,
        val asset: Issued<Currency>,
        val dDate: LocalDate, 
        val grade: Char,
        val lotSize: Int,
        val maturityDate: Instant 

):OwnableState, QueryableState, FuturesContractState, FungibleAssets {

    override val contract = CP_PROGRAM_ID //Do we need this ???

    override val participants: List<AbstractParty>
        get() = listOf(owner)

    val token: Issued<Terms>
        get() = Issued(issuance, Terms(faceValue.token, maturityDate, grade))

    override fun withNewOwner(newOwner: AbstractParty) = Pair(Commands.Move(), copy(owner = newOwner))
    override fun toString() = "${Emoji.newspaper}Contract(of $lotSize redeemable on $maturityDate by '$issuance', owned by $owner)"

    // Although kotlin is smart enough not to need these, as we are using the ICommercialPaperState, we need to declare them explicitly for use later,

    // The next three lines are suspect for requirement that point I think is to create a new "ICommercialPaperState" == "FuturesContractState"  in our case 
    //    check in any anomalies in this description arise given above

    override fun withOwner(newOwner: AbstractParty): FuturesContractState = copy(owner = newOwner)
    override fun withFaceValue(newFaceValue: Amount<Issued<Currency>>): FuturesContractState = copy(faceValue = newFaceValue)
    override fun withMaturityDate(newMaturityDate: Instant): FuturesContractState = copy(maturityDate = newMaturityDate)

    //----

    



 }

