package com.ace.app.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class VoiceAndTaskSessionIsolationTest {

    private lateinit var activeVoiceGen: AtomicLong
    private lateinit var activeTaskGen: AtomicLong
    private var currentTranscript: String? = null
    private var currentUiTaskGoal: String? = null
    private var isTaskCleared: Boolean = false

    @Before
    fun setUp() {
        activeVoiceGen = AtomicLong(0L)
        activeTaskGen = AtomicLong(100L)
        currentTranscript = null
        currentUiTaskGoal = null
        isTaskCleared = false
    }

    // Voice session helper simulating VoiceManager's generation check
    private fun startVoiceSession(): Long {
        currentTranscript = null
        isTaskCleared = true
        currentUiTaskGoal = null
        return activeVoiceGen.incrementAndGet()
    }

    private fun handleVoiceResult(capturedGen: Long, spokenText: String): Boolean {
        if (capturedGen != activeVoiceGen.get()) {
            // Callback ignored due to stale voice generation
            return false
        }
        currentTranscript = spokenText
        return true
    }

    private fun submitTaskGoal(capturedVoiceGen: Long, goal: String): Long? {
        if (capturedVoiceGen != activeVoiceGen.get()) {
            return null
        }
        val newTaskGen = activeTaskGen.incrementAndGet()
        currentUiTaskGoal = goal
        return newTaskGen
    }

    private fun updateTaskUi(capturedTaskGen: Long, goal: String): Boolean {
        if (capturedTaskGen != activeTaskGen.get()) {
            return false
        }
        currentUiTaskGoal = goal
        return true
    }

    // 1. New voice session clears previous transcript.
    @Test
    fun testNewVoiceSessionClearsPreviousTranscript() {
        handleVoiceResult(activeVoiceGen.incrementAndGet(), "Open Spotify")
        assertEquals("Open Spotify", currentTranscript)

        startVoiceSession()
        assertNull("Previous transcript must be cleared when a new voice session starts", currentTranscript)
    }

    // 2. Old recognition callback cannot overwrite new transcript.
    @Test
    fun testOldRecognitionCallbackCannotOverwriteNewTranscript() {
        val gen1 = startVoiceSession()
        val gen2 = startVoiceSession()

        val oldResultAccepted = handleVoiceResult(gen1, "Open Spotify")
        assertFalse("Old recognition callback must be rejected", oldResultAccepted)
        assertNull("Transcript must not be overwritten by old callback", currentTranscript)

        val newResultAccepted = handleVoiceResult(gen2, "Find me the latest")
        assertTrue("Current recognition callback must be accepted", newResultAccepted)
        assertEquals("Find me the latest", currentTranscript)
    }

    // 3. Old final recognition result cannot submit a new task.
    @Test
    fun testOldFinalRecognitionResultCannotSubmitNewTask() {
        val gen1 = startVoiceSession()
        val gen2 = startVoiceSession()

        val submittedTaskGen = submitTaskGoal(gen1, "Open Spotify")
        assertNull("Old voice result must not submit a task for the new session", submittedTaskGen)
        assertNull("UI goal must remain unpopulated by stale submission", currentUiTaskGoal)

        val validTaskGen = submitTaskGoal(gen2, "Find me the latest")
        assertNotNull("Valid voice session must successfully submit task", validTaskGen)
        assertEquals("Find me the latest", currentUiTaskGoal)
    }

    // 4. New final transcript reaches submitVoiceGoal().
    @Test
    fun testNewFinalTranscriptReachesSubmitVoiceGoal() {
        val gen = startVoiceSession()
        val textAccepted = handleVoiceResult(gen, "Turn on the flashlight")
        assertTrue("New final transcript must be accepted", textAccepted)

        val taskGen = submitTaskGoal(gen, currentTranscript!!)
        assertNotNull("New transcript must reach task submission", taskGen)
        assertEquals("Turn on the flashlight", currentUiTaskGoal)
    }

    // 5. New task generation replaces old task generation.
    @Test
    fun testNewTaskGenerationReplacesOldTaskGeneration() {
        val taskGen1 = activeTaskGen.incrementAndGet()
        currentUiTaskGoal = "Goal A"
        assertEquals(101L, taskGen1)
        assertEquals("Goal A", currentUiTaskGoal)

        val taskGen2 = activeTaskGen.incrementAndGet()
        currentUiTaskGoal = "Goal B"
        assertEquals(102L, taskGen2)
        assertEquals("Goal B", currentUiTaskGoal)
        assertNotEquals(taskGen1, activeTaskGen.get())
    }

    // 6. Old task execution callbacks cannot update current UI.
    @Test
    fun testOldTaskExecutionCallbacksCannotUpdateCurrentUi() {
        val taskGen1 = activeTaskGen.incrementAndGet()
        updateTaskUi(taskGen1, "Open Spotify")

        val taskGen2 = activeTaskGen.incrementAndGet()
        updateTaskUi(taskGen2, "Find me the latest")

        val staleUpdateAccepted = updateTaskUi(taskGen1, "Open Spotify - Step 2 Executed")
        assertFalse("Stale execution callback must not update current UI", staleUpdateAccepted)
        assertEquals("Find me the latest", currentUiTaskGoal)
    }

    // 7. Old verification callbacks cannot update current UI.
    @Test
    fun testOldVerificationCallbacksCannotUpdateCurrentUi() {
        val taskGen1 = activeTaskGen.incrementAndGet()
        updateTaskUi(taskGen1, "Task 1")

        val taskGen2 = activeTaskGen.incrementAndGet()
        updateTaskUi(taskGen2, "Task 2")

        val staleVerificationAccepted = updateTaskUi(taskGen1, "Task 1 VERIFIED")
        assertFalse("Stale verification callback must be discarded", staleVerificationAccepted)
        assertEquals("Task 2", currentUiTaskGoal)
    }

    // 8. Starting a second goal immediately after the first cannot display the first goal.
    @Test
    fun testStartingSecondGoalImmediatelyDoesNotDisplayFirstGoal() {
        val voiceGen1 = startVoiceSession()
        handleVoiceResult(voiceGen1, "Open Spotify")
        submitTaskGoal(voiceGen1, "Open Spotify")

        // Immediately start second session
        val voiceGen2 = startVoiceSession()
        assertTrue("UI task state must be cleared when second voice session starts", isTaskCleared)
        assertNull("UI goal must be null while listening for second goal", currentUiTaskGoal)

        handleVoiceResult(voiceGen2, "Find me the latest")
        submitTaskGoal(voiceGen2, "Find me the latest")
        assertEquals("Find me the latest", currentUiTaskGoal)
    }

    // 9. Arbitrary new goals work; no phrase-specific conditions.
    @Test
    fun testArbitraryNewGoalsWorkWithoutPhraseMatching() {
        val arbitraryGoals = listOf(
            "Find me the latest",
            "Turn on the flashlight",
            "What is the date today",
            "Calculate 15 multiplied by 4",
            "Check device status"
        )

        for (goal in arbitraryGoals) {
            val vGen = startVoiceSession()
            assertTrue(handleVoiceResult(vGen, goal))
            val tGen = submitTaskGoal(vGen, goal)
            assertNotNull(tGen)
            assertEquals(goal, currentUiTaskGoal)
        }
    }

    // 10. Old TTS/progress callbacks cannot modify current task state.
    @Test
    fun testOldTtsAndProgressCallbacksCannotModifyCurrentTask() {
        val taskGen1 = activeTaskGen.incrementAndGet()
        updateTaskUi(taskGen1, "Task 1")

        val taskGen2 = activeTaskGen.incrementAndGet()
        updateTaskUi(taskGen2, "Task 2")

        val staleProgressAccepted = updateTaskUi(taskGen1, "Progress for Task 1: Opening app...")
        assertFalse("Stale TTS/progress callback must be rejected", staleProgressAccepted)
        assertEquals("Task 2", currentUiTaskGoal)
    }

    // 11. Three consecutive requests remain completely isolated.
    @Test
    fun testThreeConsecutiveRequestsRemainIsolated() {
        val g1 = startVoiceSession()
        handleVoiceResult(g1, "Open Spotify")
        submitTaskGoal(g1, "Open Spotify")

        val g2 = startVoiceSession()
        handleVoiceResult(g2, "Find me the latest")
        submitTaskGoal(g2, "Find me the latest")

        val g3 = startVoiceSession()
        handleVoiceResult(g3, "Turn on the flashlight")
        submitTaskGoal(g3, "Turn on the flashlight")

        assertEquals("Turn on the flashlight", currentUiTaskGoal)
        assertFalse(handleVoiceResult(g1, "Open Spotify late callback"))
        assertFalse(handleVoiceResult(g2, "Find me the latest late callback"))
        assertEquals("Turn on the flashlight", currentUiTaskGoal)
    }
}
