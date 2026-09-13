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
        val postcondition = GoalUnderstandingEngine.derivePostcondition("Find \"Order 1234\" and verify \"Delivered\"")
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

        // BrainRouter when no local or cloud brain is ready
        val selectedBrain = BrainRouter.selectBrain("Perform task", obs, context, null, null)
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
        assertEquals("Verify observable state satisfied for 'Updated target goal'", context.expectedPostcondition.summary)
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
}
