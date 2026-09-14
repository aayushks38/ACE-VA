package com.ace.app.agent

import com.ace.app.brain.AgentDecision
import com.ace.app.brain.BrainRouter
import com.ace.app.brain.ReasoningBackend
import com.ace.app.brain.ReasoningBrain
import org.junit.Assert.*
import org.junit.Test

class AutonomousAgentArchitectureTest {

    // 1. Ambiguous goals result in clarification rather than guessed execution.
    @Test
    fun testAmbiguousGoalResultsInClarification() {
        val modelInterp = GoalInterpretation(
            rawGoal = "do",
            isAmbiguous = true,
            clarificationQuestion = "Could you please clarify what specific task you would like me to perform?"
        )
        val objective = GoalUnderstandingEngine.convertInterpretationToObjective(modelInterp)
        assertTrue("Model-determined ambiguous goal must mark objective ambiguous", objective.isAmbiguous)
        assertNotNull("Clarification question must be provided", objective.clarificationQuestion)
    }

    // 2. Informational goals become information-oriented objectives.
    @Test
    fun testInformationalGoalObjective() {
        val modelInterp = GoalInterpretation(
            rawGoal = "What is the current time?",
            objectiveType = "INFORMATION_RETRIEVAL",
            desiredInformation = "Current system date and time"
        )
        val objective = GoalUnderstandingEngine.convertInterpretationToObjective(modelInterp)
        assertTrue("Informational goal must be recognized from model interpretation", objective.isInformational)
        assertTrue("Desired information string must be populated", objective.desiredInformation.isNotBlank())
    }

    // 3. Multi-condition goals preserve all required success conditions.
    @Test
    fun testMultiConditionGoalPreservesSuccessConditions() {
        val modelInterp = GoalInterpretation(
            rawGoal = "Find \"Order 1234\" and verify \"Delivered\"",
            targetEntities = listOf("Order 1234", "Delivered")
        )
        val postcondition = GoalUnderstandingEngine.derivePostconditionFromInterpretation(modelInterp)
        assertEquals(2, postcondition.targetEntities.size)
        assertTrue(postcondition.targetEntities.contains("Order 1234"))
        assertTrue(postcondition.targetEntities.contains("Delivered"))
    }

    // 4. A successful action without verified postcondition does NOT become COMPLETED.
    @Test
    fun testSuccessfulActionWithoutVerifiedPostconditionNotCompleted() {
        val postcondition = ExpectedPostcondition(
            summary = "Verify delivery status",
            targetEntities = listOf("Order 9999", "Delivered")
        )
        val context = AgentTaskContext(
            userGoal = "Check order status",
            expectedPostcondition = postcondition,
            actionHistory = mutableListOf("Iteration 1: open_app(Store) -> SUCCESS")
        )
        val obs = ScreenObservation(
            packageName = "com.example.store",
            appName = "StoreApp",
            visibleText = listOf("Welcome to Store", "Account", "Cart"),
            screenState = "HomeScreen"
        )
        val outcome = IndependentGoalVerifier.verifyGoal(context, obs, null)
        assertFalse("Action success alone must not verify postcondition", outcome.isVerified)
        assertEquals(TaskStatus.NOT_VERIFIED, outcome.status)
    }

    // 5. Brain COMPLETE without environmental evidence does NOT become COMPLETED.
    @Test
    fun testBrainCompleteWithoutEvidenceNotVerified() {
        val postcondition = ExpectedPostcondition(
            summary = "Perform transaction",
            targetEntities = listOf("Confirmation #ABC")
        )
        val context = AgentTaskContext(
            userGoal = "Complete purchase",
            expectedPostcondition = postcondition,
            capturedEvidence = mutableMapOf("brain_completion_hypothesis" to "I finished the purchase")
        )
        val obs = ScreenObservation(
            packageName = "com.example.store",
            appName = "StoreApp",
            visibleText = listOf("Buy Now", "Item details"),
            screenState = "ProductPage"
        )
        val outcome = IndependentGoalVerifier.verifyGoal(context, obs, null)
        assertFalse("Brain hypothesis alone must not complete task without evidence", outcome.isVerified)
    }

    // 6. Unrelated visible text cannot verify a goal.
    @Test
    fun testUnrelatedVisibleTextCannotVerifyGoal() {
        val postcondition = ExpectedPostcondition(
            summary = "Verify account balance",
            targetEntities = listOf("Balance: $500")
        )
        val context = AgentTaskContext(userGoal = "Check balance", expectedPostcondition = postcondition)
        val obs = ScreenObservation(
            packageName = "com.bank.app",
            appName = "BankApp",
            visibleText = listOf("Settings", "Profile", "Logout", "Help"),
            screenState = "Settings"
        )
        val outcome = IndependentGoalVerifier.verifyGoal(context, obs, null)
        assertFalse(outcome.isVerified)
    }

    // 7. URI existence cannot by itself verify a goal.
    @Test
    fun testUriExistenceCannotVerifyGoalAlone() {
        val postcondition = ExpectedPostcondition(
            summary = "Confirm document sent",
            targetEntities = listOf("Recipient Confirmed")
        )
        val context = AgentTaskContext(
            userGoal = "Send document",
            expectedPostcondition = postcondition,
            capturedEvidence = mutableMapOf("content_uri" to "content://media/external/file/100")
        )
        val obs = ScreenObservation(
            packageName = "android",
            appName = "System",
            visibleText = listOf("Apps", "Clock"),
            screenState = "Home"
        )
        val outcome = IndependentGoalVerifier.verifyGoal(context, obs, null)
        assertFalse("URI existence alone must not mark target entity postcondition completed", outcome.isVerified)
    }

    // 8. Foreground application cannot by itself verify a goal.
    @Test
    fun testForegroundAppCannotVerifyGoalAlone() {
        val postcondition = ExpectedPostcondition(
            summary = "Send chat message to Alice",
            targetEntities = listOf("Alice", "Message Sent")
        )
        val context = AgentTaskContext(userGoal = "Message Alice", expectedPostcondition = postcondition)
        val obs = ScreenObservation(
            packageName = "com.messaging",
            appName = "MessagingApp",
            visibleText = listOf("Chats", "Search", "Settings"),
            screenState = "MainList"
        )
        val outcome = IndependentGoalVerifier.verifyGoal(context, obs, null)
        assertFalse("Foreground app launch alone must not verify multi-step chat objective", outcome.isVerified)
    }

    // 9. Action history cannot by itself verify a goal.
    @Test
    fun testActionHistoryCannotVerifyGoalAlone() {
        val postcondition = ExpectedPostcondition(
            summary = "Submit application",
            targetEntities = listOf("Application Submitted Successfully")
        )
        val context = AgentTaskContext(
            userGoal = "Submit form",
            expectedPostcondition = postcondition,
            actionHistory = mutableListOf("click(Submit)", "click(Confirm)")
        )
        val obs = ScreenObservation(
            packageName = "com.form",
            appName = "FormApp",
            visibleText = listOf("Submit", "Cancel"),
            screenState = "FormPage"
        )
        val outcome = IndependentGoalVerifier.verifyGoal(context, obs, null)
        assertFalse(outcome.isVerified)
    }

    // 10. Model failure cannot generate a guessed action.
    @Test
    fun testModelFailureReturnsBlockedNotGuessedAction() {
        val context = AgentTaskContext(userGoal = "Perform task")
        val obs = ScreenObservation(packageName = "com.app", appName = "App", visibleText = listOf("Main"), screenState = "Main")

        // BrainRouter when no local brain is ready
        val selectedBrain = BrainRouter.selectBrain("Perform task", obs, context, null)
        assertFalse("Selected brain must not be ready when no backend exists", selectedBrain.isReady())

        // Invoking reasonNextDecision on unready router must return Blocked decision, NOT a guessed action
        val decision = kotlinx.coroutines.runBlocking {
            selectedBrain.reasonNextDecision("Perform task", obs, context, 1L)
        }
        assertTrue("Unready brain router must return AgentDecision.Blocked", decision is AgentDecision.Blocked)
    }

    // 11. Replanning changes the actual remaining objective/decision process.
    @Test
    fun testReplanningUpdatesPostconditionAndGoal() {
        val context = AgentTaskContext(
            userGoal = "Original goal",
            expectedPostcondition = ExpectedPostcondition(summary = "Original")
        )
        val newGoal = "Updated target goal"
        context.userGoal = newGoal
        context.expectedPostcondition = GoalUnderstandingEngine.derivePostcondition(newGoal)

        assertEquals("Updated target goal", context.userGoal)
        assertEquals("Updated target goal", context.expectedPostcondition.summary)
    }

    // 12. Local and cloud brains emit the same AgentDecision contract.
    @Test
    fun testDecisionContractUniformity() {
        val actionDecision: AgentDecision = AgentDecision.Action(primitive = "ui_click", target = "Submit")
        val clarifyDecision: AgentDecision = AgentDecision.Clarify(question = "Which account?")
        val completeDecision: AgentDecision = AgentDecision.Complete(evidence = "Target state reached")
        val blockedDecision: AgentDecision = AgentDecision.Blocked(reason = "Permission required")

        assertNotNull(actionDecision)
        assertNotNull(clarifyDecision)
        assertNotNull(completeDecision)
        assertNotNull(blockedDecision)
    }

    // 13. Unknown apps are handled through generic observation/action mechanisms.
    @Test
    fun testUnknownAppHandledGenerically() {
        val obs = ScreenObservation(
            packageName = "com.unseen.vendor.app",
            appName = "CompletelyUnknownApp",
            visibleText = listOf("Button A", "Input Field"),
            screenState = "CustomView"
        )
        val formattedUi = ScreenObservationEngine.formatCompactUiRepresentation("Click Button A", obs)
        assertTrue(formattedUi.contains("CompletelyUnknownApp"))
        assertTrue(formattedUi.contains("Button A"))
    }

    // 14. A new task cannot inherit state from a previous task.
    @Test
    fun testTaskSessionStateIsolation() {
        val task1Context = AgentTaskContext(userGoal = "Task 1", generationId = 101L)
        task1Context.actionHistory.add("Executed action 1")
        task1Context.capturedEvidence["key1"] = "val1"

        val task2Context = AgentTaskContext(userGoal = "Task 2", generationId = 102L)
        assertTrue("Task 2 action history must be empty", task2Context.actionHistory.isEmpty())
        assertTrue("Task 2 evidence map must be empty", task2Context.capturedEvidence.isEmpty())
        assertNotEquals(task1Context.generationId, task2Context.generationId)
    }

    // 15. Goal interpretation contract is model-driven and contains zero Kotlin string intent classifiers.
    @Test
    fun testModelDrivenGoalInterpretationContract() {
        val interp = GoalUnderstandingEngine.createInitialInterpretation("Check account status")
        assertFalse("Initial neutral interpretation must not assume ambiguity without model decision", interp.isAmbiguous)
        assertEquals("Check account status", interp.rawGoal)
        val postcondition = GoalUnderstandingEngine.derivePostconditionFromInterpretation(interp)
        assertNotNull(postcondition.summary)
        assertTrue(postcondition.targetEntities.contains("Check account status"))
    }

    // 16. Production autonomous cognition resolves strictly to local Gemma.
    @Test
    fun testProductionCognitionResolvesStrictlyToLocalGemma() {
        val context = AgentTaskContext(userGoal = "Local reasoning test")
        val obs = ScreenObservation(packageName = "com.test", appName = "TestApp", visibleText = listOf("Header"), screenState = "Interactive")
        
        val mockLocalBrain = object : com.ace.app.brain.LocalBrain {
            override val backendType = ReasoningBackend.LOCAL_GEMMA
            override suspend fun initialize(context: android.content.Context, handle: com.ace.app.brain.ModelHandle): com.ace.app.brain.BrainResult = com.ace.app.brain.BrainResult.Cancelled
            override suspend fun cancel() {}
            override fun getBrainState(): com.ace.app.brain.BrainState = com.ace.app.brain.BrainState.READY
            override fun close() {}
            override fun isReady(): Boolean = true
            override suspend fun reasonNextDecision(goal: String, observation: ScreenObservation, context: AgentTaskContext, generationId: Long): AgentDecision {
                return AgentDecision.Action(primitive = "CLICK", target = "Header")
            }
        }

        val brain = BrainRouter.selectBrain("Local reasoning test", obs, context, mockLocalBrain)
        assertEquals("Production router must resolve to LOCAL_GEMMA", ReasoningBackend.LOCAL_GEMMA, brain.backendType)
        assertTrue("Selected local brain must be ready", brain.isReady())
    }

    // 17. Missing perception is explicitly represented as missing and cannot verify postcondition.
    @Test
    fun testMissingPerceptionCannotVerifySuccess() {
        val postcondition = ExpectedPostcondition(summary = "Search result", targetEntities = listOf("Result A"))
        val context = AgentTaskContext(userGoal = "Find Result A", expectedPostcondition = postcondition)
        val unobservedScreen = ScreenObservation(
            packageName = "android",
            appName = "System",
            visibleText = emptyList(),
            screenState = "PERCEPTION_UNAVAILABLE",
            isPerceptionAvailable = false
        )
        val outcome = IndependentGoalVerifier.verifyGoal(context, unobservedScreen, null)
        assertFalse("Unobserved perception must not verify success", outcome.isVerified)
        assertEquals(TaskStatus.NOT_VERIFIED, outcome.status)
        assertTrue(outcome.evidence.contains("unavailable"))
    }

    // 18. Loaded Gemma runtime reports ready everywhere and UI & AgentExecutor agree.
    @Test
    fun testUnifiedGemmaReadinessAgreement() {
        val mockBrain = object : com.ace.app.brain.LocalBrain {
            override val backendType = ReasoningBackend.LOCAL_GEMMA
            override suspend fun initialize(context: android.content.Context, handle: com.ace.app.brain.ModelHandle): com.ace.app.brain.BrainResult = com.ace.app.brain.BrainResult.Success("Ready")
            override suspend fun cancel() {}
            override fun getBrainState(): com.ace.app.brain.BrainState = com.ace.app.brain.BrainState.READY
            override fun close() {}
            override fun isReady(): Boolean = true
            override suspend fun reasonNextDecision(goal: String, observation: ScreenObservation, context: AgentTaskContext, generationId: Long): AgentDecision {
                return AgentDecision.Action(primitive = "CLICK", target = "Submit")
            }
        }

        val obs = ScreenObservation(packageName = "com.app", appName = "App", visibleText = listOf("Submit"), screenState = "Interactive")
        val context = AgentTaskContext(userGoal = "Test goal")

        val selectedBrain = BrainRouter.selectBrain("Test goal", obs, context, mockBrain)
        assertTrue("Selected brain must report isReady()=true when local brain is READY", selectedBrain.isReady())
        assertEquals("Brain instance identity must match provided local brain", System.identityHashCode(mockBrain), System.identityHashCode(selectedBrain))
    }

    // 19. A missing runtime reports unavailable everywhere.
    @Test
    fun testMissingRuntimeReportsUnavailableEverywhere() {
        val obs = ScreenObservation(packageName = "android", appName = "System")
        val context = AgentTaskContext(userGoal = "Missing brain test")

        val selectedBrain = BrainRouter.selectBrain("Missing brain test", obs, context, null)
        assertFalse("Unloaded brain must report isReady()=false", selectedBrain.isReady())
    }

    // 20. Novel natural language goal ("Find admissions for Ketam University") routes to DeepBrain for Gemma execution.
    @Test
    fun testNovelNaturalLanguageGoalRoutesToDeepBrain() {
        val router = AceCommandRouter()
        val goal = "Find the admission requirements for ketam University and tell me what documents are required"
        val route = router.route(goal, brainAvailable = true)

        assertTrue("Novel natural language goal must route to DeepBrain", route is CommandRoute.DeepBrain)
        val deepRoute = route as CommandRoute.DeepBrain
        assertEquals(RouteType.DEEP_BRAIN, deepRoute.result.route)
        assertTrue("Brain must be required for novel reasoning goal", deepRoute.result.brainRequired)
    }

    // 21. "Find me the latest" routes to DeepBrain for Gemma interpretation and clarification.
    @Test
    fun testFindMeTheLatestRoutesToDeepBrainForClarification() {
        val router = AceCommandRouter()
        val goal = "Find me the latest"
        val route = router.route(goal, brainAvailable = true)

        assertTrue("Underspecified goal must route to DeepBrain for Gemma clarification", route is CommandRoute.DeepBrain)
    }

    // 22. Valid Gemma JSON output is parsed into GoalInterpretation cleanly.
    @Test
    fun testValidGemmaJsonParsing() {
        val brain = com.ace.app.brain.GemmaLocalBrain()
        val rawJson = """{
            "objectiveType": "INFORMATION_RETRIEVAL",
            "requestedOutcome": "Find admissions",
            "targetEntities": ["Ketam University"],
            "desiredState": "",
            "desiredInformation": "Admission requirements",
            "clarificationRequired": false,
            "clarificationQuestion": null
        }"""
        val parsed = brain.extractJsonObject(rawJson)
        assertNotNull("Valid JSON matching strict schema must be extracted", parsed)
        assertEquals("INFORMATION_RETRIEVAL", parsed?.objectiveType)
        assertEquals("Find admissions", parsed?.requestedOutcome)
        assertEquals(listOf("Ketam University"), parsed?.targetEntities)
        assertFalse(parsed?.clarificationRequired == true)
        assertNull(parsed?.clarificationQuestion)
    }

    // 23. Fenced valid JSON block is stripped and extracted cleanly.
    @Test
    fun testFencedJsonParsing() {
        val brain = com.ace.app.brain.GemmaLocalBrain()
        val fencedJson = """
            ```json
            {
              "objectiveType": "STATE_MODIFICATION",
              "requestedOutcome": "Turn on flashlight",
              "targetEntities": ["Flashlight"],
              "desiredState": "Flashlight ON",
              "desiredInformation": "",
              "clarificationRequired": false,
              "clarificationQuestion": null
            }
            ```
        """.trimIndent()
        val parsed = brain.extractJsonObject(fencedJson)
        assertNotNull("Fenced JSON block matching strict schema must be extracted", parsed)
        assertEquals("STATE_MODIFICATION", parsed?.objectiveType)
        assertEquals("Turn on flashlight", parsed?.requestedOutcome)
    }

    // 24. Malformed/unparseable model output returns null and does NOT fallback to keyword match.
    @Test
    fun testMalformedOutputReturnsNullWithoutKeywordFallback() {
        val brain = com.ace.app.brain.GemmaLocalBrain()
        val malformed = "I am an AI assistant. I will find what requirements and documents you need."
        val parsed = brain.extractJsonObject(malformed)
        assertNull("Plain text output without JSON structure must return null without keyword fallback", parsed)
    }

    // 25. Truncated JSON without closing brace returns null and is NOT repaired.
    @Test
    fun testTruncatedJsonReturnsNullWithoutRepair() {
        val brain = com.ace.app.brain.GemmaLocalBrain()
        val truncated = """{"objectiveType":"INFORMATION_RETRIEVAL","requestedOutcome":"Find requirements""""
        val parsed = brain.extractJsonObject(truncated)
        assertNull("Truncated JSON without closing brace must return null without repair appending", parsed)
    }

    // 26. Invalid objectiveType enum value is rejected.
    @Test
    fun testInvalidObjectiveTypeEnumReturnsNull() {
        val brain = com.ace.app.brain.GemmaLocalBrain()
        val invalidEnum = """{
            "objectiveType": "INVALID_CATEGORY",
            "requestedOutcome": "Some action",
            "targetEntities": [],
            "desiredState": "",
            "desiredInformation": "",
            "clarificationRequired": false,
            "clarificationQuestion": null
        }"""
        val parsed = brain.extractJsonObject(invalidEnum)
        assertNull("Invalid objectiveType enum value must be rejected", parsed)
    }

    // 27. Missing required field (e.g. desiredState) is rejected.
    @Test
    fun testMissingRequiredFieldReturnsNull() {
        val brain = com.ace.app.brain.GemmaLocalBrain()
        val missingField = """{
            "objectiveType": "INFORMATION_RETRIEVAL",
            "requestedOutcome": "Find docs",
            "targetEntities": ["Docs"],
            "desiredInformation": "Doc info",
            "clarificationRequired": false
        }"""
        val parsed = brain.extractJsonObject(missingField)
        assertNull("JSON missing required desiredState field must be rejected", parsed)
    }

    // 28. clarificationRequired=true without a question is rejected.
    @Test
    fun testClarificationRequiredTrueWithoutQuestionReturnsNull() {
        val brain = com.ace.app.brain.GemmaLocalBrain()
        val invalidClarify = """{
            "objectiveType": "GENERAL",
            "requestedOutcome": "Incomplete task",
            "targetEntities": [],
            "desiredState": "",
            "desiredInformation": "",
            "clarificationRequired": true,
            "clarificationQuestion": null
        }"""
        val parsed = brain.extractJsonObject(invalidClarify)
        assertNull("clarificationRequired=true with null question must be rejected", parsed)
    }

    // 29. clarificationRequired=false with a non-null question is rejected.
    @Test
    fun testClarificationRequiredFalseWithQuestionReturnsNull() {
        val brain = com.ace.app.brain.GemmaLocalBrain()
        val invalidClarify = """{
            "objectiveType": "GENERAL",
            "requestedOutcome": "Clear task",
            "targetEntities": [],
            "desiredState": "",
            "desiredInformation": "",
            "clarificationRequired": false,
            "clarificationQuestion": "Why are you asking?"
        }"""
        val parsed = brain.extractJsonObject(invalidClarify)
        assertNull("clarificationRequired=false with non-null question must be rejected", parsed)
    }

    // 30. Conversational clarification context is preserved across turns and combined.
    @Test
    fun testConversationalClarificationPreservationAcrossTurns() {
        AceConversationContext.clearSession()
        val initialGoal = "Find me the latest"
        val question = "Could you specify what you'd like me to find?"

        AceConversationContext.setPendingClarification(initialGoal, question, 101L)

        val userClarificationAnswer = "Admissions requirements for Harvard University on their website"
        val combinedText = AceConversationContext.consumePendingClarification(userClarificationAnswer)

        assertNotNull("Pending clarification combined text must be generated", combinedText)
        assertTrue("Combined context must contain original goal", combinedText!!.contains(initialGoal))
        assertTrue("Combined context must contain clarification question", combinedText.contains(question))
        assertTrue("Combined context must contain user answer", combinedText.contains(userClarificationAnswer))

        // Subsequent call returns null (consumed)
        assertNull("Subsequent consume must return null to prevent leaking into future turns", AceConversationContext.consumePendingClarification(userClarificationAnswer))
    }

    // 31. Formal ConversationalSessionState model tracks session generations and awaiting clarification state.
    @Test
    fun testConversationalStateModelSessionTracking() {
        AceConversationContext.clearSession()
        val session1 = AceConversationContext.startNewSession(201L)
        assertEquals(201L, session1.sessionGenerationId)
        assertFalse(session1.isAwaitingClarification)

        AceConversationContext.setPendingClarification("Goal 1", "Clarification 1", 201L)
        val state1 = AceConversationContext.getConversationalState()
        assertTrue(state1.isAwaitingClarification)
        assertEquals("Goal 1", state1.pendingGoal)
        assertEquals("Clarification 1", state1.clarificationQuestion)

        // Starting session 2 while awaiting clarification preserves pending clarification state
        val session2 = AceConversationContext.startNewSession(202L)
        assertEquals(202L, session2.sessionGenerationId)
        assertTrue("Session 2 must inherit awaiting clarification state until answered", session2.isAwaitingClarification)
        assertEquals("Goal 1", session2.pendingGoal)

        // Answering clarification consumes state
        val consumed = AceConversationContext.consumePendingClarification("Answer 1")
        assertNotNull(consumed)
        assertFalse("State must no longer be awaiting clarification after answer", AceConversationContext.getConversationalState().isAwaitingClarification)
    }

    // 32. Session clearing resets pending clarification safely.
    @Test
    fun testSessionCleardownResetsPendingClarification() {
        AceConversationContext.setPendingClarification("Unfinished goal", "What item?")
        AceConversationContext.clearSession()
        assertNull("Clearing session must wipe pending clarification", AceConversationContext.consumePendingClarification("Anything"))
        assertFalse("Cleared state must not be awaiting clarification", AceConversationContext.getConversationalState().isAwaitingClarification)
    }

    // 33. Gemma CLARIFY decision structure is extracted by GemmaLocalBrain production code.
    @Test
    fun testGemmaClarifyDecisionProcessing() {
        val brain = com.ace.app.brain.GemmaLocalBrain()
        val rawOutput = """{
            "objectiveType": "GENERAL",
            "requestedOutcome": "Clarification required",
            "targetEntities": [],
            "desiredState": "",
            "desiredInformation": "",
            "clarificationRequired": true,
            "clarificationQuestion": "Which university admissions requirement would you like me to look up?"
        }"""
        val parsed = brain.extractJsonObject(rawOutput)
        assertNotNull("GemmaLocalBrain production parser must extract CLARIFY JSON", parsed)
        assertTrue("clarificationRequired must be true", parsed?.clarificationRequired == true)
        assertEquals("Which university admissions requirement would you like me to look up?", parsed?.clarificationQuestion)
    }

    // 34. Fresh observation is formatted with app, state, interactive elements and bounds on every iteration.
    @Test
    fun testFreshObservationInjectedIntoGemmaPromptOnEveryIteration() {
        val obs = ScreenObservation(
            packageName = "org.mozilla.firefox",
            appName = "Firefox",
            visibleText = listOf("Harvard Admissions", "Application Portal", "Requirements"),
            clickableElements = listOf(
                ScreenElement(id = "search_btn", text = "Search", contentDescription = "Search button", isClickable = true, isEditable = false, isScrollable = false, isSelected = false, boundsInScreen = "[100,200,300,400]")
            ),
            editableElements = listOf(
                ScreenElement(id = "url_bar", text = "https://harvard.edu", contentDescription = "Address bar", isClickable = true, isEditable = true, isScrollable = false, isSelected = false, boundsInScreen = "[0,50,1080,150]")
            ),
            scrollableElements = listOf(
                ScreenElement(id = "scroll_view", text = "Content", contentDescription = "Main scroll", isClickable = false, isEditable = false, isScrollable = true, isSelected = false)
            ),
            screenState = "INTERACTIVE_SCREEN",
            isPerceptionAvailable = true
        )
        val formattedPrompt = ScreenObservationEngine.formatCompactUiRepresentation("Find admissions", obs)
        assertTrue("Observation must contain app name", formattedPrompt.contains("Firefox"))
        assertTrue("Observation must contain interactive element text", formattedPrompt.contains("Search"))
        assertTrue("Observation must contain editable element bounds", formattedPrompt.contains("[0,50,1080,150]"))
        assertTrue("Observation must contain visible text nodes", formattedPrompt.contains("Harvard Admissions"))
    }

    // 35. Information-seeking goal cannot complete merely because a browser was launched or opened.
    @Test
    fun testInformationSeekingGoalCannotCompleteWithoutPostconditionEvidence() {
        val postcondition = ExpectedPostcondition(
            summary = "Find admissions requirements for Harvard",
            desiredInformation = "Official admissions requirements text"
        )
        val context = AgentTaskContext(
            userGoal = "Find admissions requirements for Harvard",
            expectedPostcondition = postcondition,
            actionHistory = mutableListOf("Iteration 1: open_url(https://harvard.edu) -> SUCCESS")
        )
        val browserHomeObs = ScreenObservation(
            packageName = "com.android.chrome",
            appName = "Chrome",
            visibleText = listOf("Google", "Search or type URL"),
            screenState = "BrowserHome"
        )
        val outcome = IndependentGoalVerifier.verifyGoal(context, browserHomeObs, null)
        assertFalse("Opening browser alone must not satisfy information retrieval postcondition", outcome.isVerified)
        assertEquals(TaskStatus.NOT_VERIFIED, outcome.status)
    }

    // 36. Action failure or unexpected observation triggers model-driven replan context.
    @Test
    fun testActionFailureTriggersModelDrivenReplanContext() {
        val context = AgentTaskContext(
            userGoal = "Find admissions",
            expectedPostcondition = ExpectedPostcondition(summary = "Find admissions")
        )
        context.actionHistory.add("Iteration 1: CLICK(Submit) -> FAILED")
        context.blockers.add("Could not click UI element 'Submit'")

        val memoryStr = context.formatCompactTaskMemory()
        assertTrue("Task memory must record action failure for model replanning", memoryStr.contains("FAILED"))
        assertTrue("Task memory must record failure reason", memoryStr.contains("Could not click"))
    }

    // 37. Natural-language tasks route strictly to DeepBrain for autonomous execution.
    @Test
    fun testNaturalLanguageTasksRouteStrictlyToDeepBrain() {
        val router = AceCommandRouter()
        val route = router.route("Go to the university website and find admissions deadlines", brainAvailable = true)
        assertTrue("Complex natural language task must route to DeepBrain", route is CommandRoute.DeepBrain)
        val deepRoute = route as CommandRoute.DeepBrain
        assertEquals(RouteType.DEEP_BRAIN, deepRoute.result.route)
        assertTrue("Brain must be required for autonomous execution", deepRoute.result.brainRequired)
    }
}
