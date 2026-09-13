# ACE — PERMANENT MASTER ENGINEERING DIRECTIVE
# Performance + Reliability + On-Device AI + Execution + Verification + APK Distribution

You are the primary engineering agent for the ACE Android project.

Treat this document as a PERMANENT workspace engineering rule.

Your job is not merely to make ACE compile. Your job is to continuously make ACE:

FAST
SMALL
MEMORY-EFFICIENT
CPU-EFFICIENT
BATTERY-EFFICIENT
RELIABLE
TRUTHFUL
OFFLINE-FIRST
LOW-LATENCY
EASY TO BUILD
EASY TO DISTRIBUTE
COMPATIBLE ACROSS SUPPORTED ANDROID DEVICES

Do not sacrifice correctness for speed.

==================================================
1. GOLDEN RULE
==================================================

For every change, prefer:

LESS CODE
LESS MEMORY
LESS CPU
LESS I/O
LESS ALLOCATION
LESS DUPLICATION
LESS STARTUP WORK
LESS BATTERY
LESS APK SIZE
LESS BUILD TIME

while preserving:

CORRECTNESS
RELIABILITY
SECURITY
USER EXPERIENCE
ANDROID COMPATIBILITY

Measure before making major optimization decisions.

Never optimize blindly.

==================================================
2. ENGINEERING PRIORITY
==================================================

Use this priority order:

1. Correctness
2. Reliability
3. Runtime performance
4. Memory efficiency
5. APK size
6. Battery efficiency
7. Incremental build speed
8. Maintainability

Never make a system faster by making its result incorrect.

==================================================
3. NEVER REBUILD EXPENSIVE COMPONENTS UNNECESSARILY
==================================================

Before changing build/native infrastructure:

INSPECT THE EXISTING IMPLEMENTATION FIRST.

Do NOT:

- run Gradle clean unnecessarily
- delete Gradle caches
- delete build directories unless required
- rebuild native llama.cpp components without a reason
- touch C/C++ code for Kotlin/UI changes
- change NDK versions unnecessarily
- change CMake configuration unnecessarily
- change ABI configuration unnecessarily
- invalidate caches unnecessarily

Prefer incremental builds.

If a change can be validated with a small Gradle task, do not automatically run a full release build.

==================================================
4. BUILD SPEED
==================================================

Optimize the development workflow for fast incremental builds.

Prefer:

- Gradle daemon
- Gradle build cache
- incremental compilation
- incremental Kotlin/KSP processing where supported
- parallel execution where safe
- configuration caching where compatible
- avoiding unnecessary dependency changes

Development workflow:

SMALL KOTLIN/UI CHANGE
→ incremental debug build

NATIVE CHANGE
→ native rebuild only when required

RELEASE/DISTRIBUTION CHANGE
→ release build + complete distribution validation

Do not repeatedly build Debug + Release unless explicitly needed.

Do not run clean builds as a default workflow.

==================================================
5. DEBUG VS RELEASE
==================================================

Use DEBUG for normal development.

Use RELEASE for final distribution validation.

Do not build both variants for every small change.

Release validation must happen before claiming the APK is distribution-ready.

==================================================
6. NATIVE BUILD STABILITY
==================================================

ACE uses native llama.cpp/Gemma inference.

Keep the native build stable.

Preserve:

- supported ABIs
- NDK compatibility
- CMake compatibility
- 16 KB page-size compatibility
- native library packaging
- JNI interfaces

Current supported distribution ABIs:

arm64-v8a
x86_64

Do not add additional ABIs unless there is a demonstrated requirement.

==================================================
7. APK SIZE
==================================================

The large GGUF model MUST NOT be packaged inside the APK.

The model is distributed separately.

Do not duplicate the model.

Do not place large model files inside:

- assets
- res/raw
- APK resources
- native libraries

Avoid unnecessary dependencies.

Before adding a dependency, evaluate:

- APK size
- method count
- startup cost
- memory cost
- build cost
- native dependencies
- maintenance cost

Prefer existing Android/Kotlin functionality when sufficient.

==================================================
8. MODEL
==================================================

ACE's intended local model is:

gemma-3n-E2B-it-Q4_0.gguf

Expected model location:

/storage/emulated/0/Download/AceModels/gemma-3n-E2B-it-Q4_0.gguf

The model must remain external to the APK.

Do not silently substitute:

- Q4_K_M
- E4B
- another Gemma variant
- another GGUF

unless explicitly instructed.

Model selection must be deterministic.

Do not accidentally select another .gguf merely because it exists in Downloads.

==================================================
9. MODEL MEMORY MANAGEMENT
==================================================

The Gemma model is large.

Avoid:

- duplicate model instances
- loading the same model multiple times
- copying the entire model into multiple byte arrays
- Base64 model representation
- unnecessary model duplication
- repeated initialization
- unnecessary model reloads

Prefer one persistent model instance.

Reuse the initialized model.

Do not initialize the model on every user command.

Avoid holding large temporary objects.

If memory pressure occurs:

- detect it
- fail gracefully
- release unnecessary resources
- avoid crashes
- report the actual state

Never claim the model is ready if it failed to load.

==================================================
10. MODEL LOADING
==================================================

Prefer efficient file access.

If shared-storage/FUSE access prevents efficient mmap, use an appropriate app-private copy strategy rather than repeatedly allocating the entire model into memory.

A model copy should:

- happen only when necessary
- be atomic/safe
- avoid duplicate copies
- be reused on subsequent launches
- preserve the correct model identity
- not silently substitute another model

Do not claim "zero memory usage."

Memory used by the native inference runtime/context still exists.

==================================================
11. INFERENCE PERFORMANCE
==================================================

Keep inference latency low.

Prefer:

- concise prompts
- limited conversation history
- bounded context
- concise structured outputs
- reuse of the model
- cancellation support
- appropriate background threads
- no duplicate inference
- no unnecessary re-parsing

Do not send huge prompts when a small structured command is sufficient.

Do not repeatedly ask Gemma to perform deterministic Android operations.

==================================================
12. HYBRID AI + DETERMINISTIC EXECUTION
==================================================

Use AI for:

- language understanding
- intent extraction
- task decomposition
- planning
- ambiguous natural-language interpretation

Use deterministic Android code for:

- flashlight
- volume
- phone calls
- app launching
- contacts
- file discovery
- URI handling
- MIME types
- permissions
- Android intents
- MediaStore
- TTS
- state management
- verification
- known system operations

Do NOT make Gemma solve something Android APIs can solve deterministically.

==================================================
13. FAST PATH
==================================================

Simple unambiguous commands should use a fast deterministic path.

Examples:

"turn on flashlight"
"open YouTube"
"open WhatsApp"
"call Ravi"

If the command is unambiguous and supported, avoid unnecessary LLM inference.

Preferred architecture:

FAST PATH
→ deterministic execution

otherwise:

LOCAL GEMMA
→ structured plan
→ deterministic execution

==================================================
14. ACE CORE ARCHITECTURE
==================================================

Maintain the conceptual architecture:

UNDERSTAND
→ PLAN
→ ACT
→ OBSERVE
→ VERIFY

The fundamental rule is:

EXECUTE ≠ SUCCESS

Sending an Android intent is NOT proof that the requested task succeeded.

ACE must distinguish:

EXECUTED
VERIFIED
UNVERIFIED
PARTIAL
BLOCKED
FAILED

Never report SUCCESS merely because an action was attempted.

==================================================
15. VERIFICATION
==================================================

Verification must use the strongest available evidence.

Prefer deterministic evidence.

Examples:

FLASHLIGHT:
verify actual flashlight state when possible.

PHONE CALL:
verify that call initiation actually occurred.

FILE SHARE:
verify correct URI/content was provided.

WHATSAPP:
verify each required stage independently.

YOUTUBE:
opening YouTube is not the same as successfully searching YouTube.

For multi-step tasks:

Step 1 → execute → observe
Step 2 → execute → observe
Step 3 → execute → observe
...
Final → verify

Do not mark the whole task COMPLETED if required steps remain unverified.

==================================================
16. VISUAL/OBSERVATION ARCHITECTURE
==================================================

Do not build ACE as a collection of permanent hardcoded app-specific hacks.

Long-term architecture:

USER VOICE
→ UNDERSTAND
→ GEMMA PLAN
→ ACTION
→ OBSERVE SCREEN
→ ACTION
→ OBSERVE
→ VERIFY
→ RESULT

App-specific deterministic handlers are acceptable as fast paths.

But the architecture should gradually evolve toward:

OBSERVE
→ ACT
→ OBSERVE
→ VERIFY

rather than:

GUESS
→ CLICK HARDCODED LOCATION
→ CLAIM SUCCESS

==================================================
17. ACCESSIBILITY
==================================================

Accessibility should be required only when the requested operation actually needs UI interaction.

Do not globally block ACE simply because Accessibility Service is disabled.

Example:

Opening YouTube:
may work without Accessibility.

Searching inside YouTube:
may require Accessibility/UI interaction.

If blocked:

report:

✓ completed steps
⏳ remaining step
⚠ exact missing permission/capability

Do not falsely report failure for steps that actually succeeded.

==================================================
18. TASK STATE
==================================================

Maintain one authoritative task state.

Preferred state flow:

IDLE
→ LISTENING
→ THINKING
→ EXECUTING
→ SPEAKING
→ IDLE

Avoid multiple competing state machines.

Avoid duplicate sources of truth.

Do not create parallel task/execution systems when an existing one can be extended.

==================================================
19. VOICE UX
==================================================

ACE is voice-first.

No wake word.

No startup greeting.

No "Yes?" acknowledgement when the user taps the orb.

Tap orb:

IDLE
→ LISTENING immediately

Do not waste latency speaking before listening.

User speech must not be unnecessarily cut off.

Use:

- reasonable silence timeout
- reasonable maximum listening duration
- cancellation support

After transcript finalization:

LISTENING
→ THINKING/EXECUTING

==================================================
20. PROGRESS SPEECH
==================================================

Progress speech is allowed during genuinely long tasks.

Examples:

"Opening WhatsApp."
"Finding Ravi."
"Sending it."

Do not spam progress messages.

Use:

- throttling
- deduplication
- coalescing
- generation/task IDs

Never speak:

"Yes?"
"Okay."
"Sure."

merely to acknowledge a tap.

Final result should be spoken through TTS.

If the user interrupts TTS:

stop TTS
→ return to listening

==================================================
21. UI PERFORMANCE
==================================================

Keep Jetpack Compose lightweight.

Do not perform heavy work inside composables.

Avoid:

- unnecessary recompositions
- unstable state
- large objects recreated every frame
- unnecessary animations
- blocking the main thread
- repeated model initialization from UI state

Keep the home screen simple.

Voice-first experience:

central orb
minimal state
minimal visual clutter

==================================================
22. STARTUP
==================================================

Optimize time-to-first-frame.

Do not initialize the heavy model unnecessarily before the UI is usable.

Prefer controlled/lazy initialization.

Avoid:

- heavy disk scanning during startup
- unnecessary network work
- expensive MediaStore queries
- unnecessary native initialization
- unnecessary dependency initialization

The UI should become usable as quickly as possible.

==================================================
23. FILE I/O
==================================================

Avoid unnecessary file copying.

Prefer:

- direct content URIs
- MediaStore
- streaming
- targeted queries
- bounded reads

Never treat a raw MediaStore ID/path string as an attachment payload.

For Android sharing:

use appropriate:

content:// URI
EXTRA_STREAM
MIME type
FLAG_GRANT_READ_URI_PERMISSION
ClipData when appropriate

==================================================
24. NETWORK
==================================================

ACE should remain offline-first whenever possible.

For network operations:

- reuse connections
- minimize payloads
- bound requests
- cache only when useful
- support cancellation
- avoid unnecessary retries

Do not make local operations depend on the network.

==================================================
25. CONCURRENCY
==================================================

Use structured concurrency.

Avoid:

- thread-per-operation
- uncontrolled coroutine creation
- duplicate background workers
- race conditions
- multiple simultaneous model loads

Only one authoritative task execution pipeline should control a user command.

Cancellation must propagate through the task.

==================================================
26. LOGGING
==================================================

Use structured, useful logs.

Log important events such as:

ACE_BRAIN
ACE_MODEL_LOAD
ACE_TASK
ACE_ACTION
ACE_VERIFY
ACE_RESULT

Do not log:

- huge model contents
- huge prompts
- unnecessary screen dumps
- sensitive user content
- repeated noisy messages

Verbose diagnostic logging should not be unnecessarily enabled in release builds.

==================================================
27. ERROR HANDLING
==================================================

Never crash because a model/action/permission is unavailable.

Prefer:

DETECT
→ CLASSIFY
→ REPORT
→ RECOVER WHEN POSSIBLE

Avoid infinite retries.

If a native/model operation fails:

- capture the error
- transition to a safe state
- release resources where appropriate
- report the actual limitation

Do not hide errors by claiming success.

==================================================
28. CACHING
==================================================

Cache only when it provides measurable value.

Never reuse stale verification state.

Do not cache:

- "flashlight is on"
- "message was sent"
- "call started"

without reliable evidence.

Cached information must have clear ownership and invalidation rules.

==================================================
29. ALGORITHMS
==================================================

Prefer the simplest effective algorithm.

Use:

- indexed queries
- direct Android APIs
- deterministic routing
- bounded searches
- efficient data structures

Avoid unnecessary:

- reflection
- polling
- full-directory scans
- repeated parsing
- duplicate conversions

==================================================
30. CODE QUALITY
==================================================

Avoid duplicate systems.

Before adding a new class:

SEARCH THE EXISTING CODEBASE.

If an existing capability can be extended, extend it.

Do not create:

- duplicate executors
- duplicate state managers
- duplicate model managers
- duplicate TTS systems
- duplicate routing systems
- duplicate verification systems

Keep components focused.

==================================================
31. BEFORE EVERY CHANGE
==================================================

ALWAYS:

1. Inspect the existing implementation.
2. Identify the actual execution path.
3. Identify the smallest correct modification.
4. Modify the existing path when possible.
5. Avoid speculative rewrites.
6. Avoid parallel implementations.
7. Preserve existing working functionality.

Do not rewrite working architecture merely for stylistic reasons.

==================================================
32. AFTER EVERY CHANGE
==================================================

Run the smallest meaningful validation.

Examples:

Kotlin change:
→ compile relevant module

UI change:
→ build/install and inspect UI

Action change:
→ run the specific action

Verification change:
→ test the specific success/failure path

Native change:
→ perform the necessary native validation

Release/distribution change:
→ perform complete release validation

Do NOT automatically run every possible build/test after every tiny change.

==================================================
33. PERFORMANCE REGRESSION CHECK
==================================================

For meaningful changes, compare:

- build time
- APK size
- startup time
- model load time
- inference latency
- task latency
- memory usage
- CPU usage
- battery impact

Do not claim an optimization without evidence when measurement is possible.

==================================================
34. NORTH STAR
==================================================

ACE's preferred execution strategy is:

FAST PATH
→ LOCAL AI
→ DETERMINISTIC ANDROID ACTIONS
→ DIRECT OBSERVATION
→ DETERMINISTIC VERIFICATION
→ FAST TRUTHFUL RESULT

The ultimate goal is:

FAST + CORRECT + VERIFIED.

==================================================
35. DISTRIBUTION / APK INSTALLATION DIRECTIVE
==================================================

The official distributable APK is:

E:\ACE__VA\app\build\outputs\apk\release\app-release.apk

The distribution artifact must be a:

SINGLE STANDALONE UNIVERSAL APK

It must NOT require:

- USB
- ADB
- Android Studio
- split APK installation
- emulator
- developer tools

The APK must support:

arm64-v8a
x86_64

The GGUF model must remain external and must NOT be packaged into the APK.

Release signing must be valid and distribution-ready.

Explicitly preserve and validate:

V1
V2
V3
V4

signature schemes as supported/configured by the Android build tooling.

Native `.so` libraries must satisfy:

16 KB alignment
0x4000 ELF LOAD alignment
appropriate ZIP alignment

Preserve current supported:

minSdk 26
targetSdk 35

Do not claim universal Android compatibility.

The correct claim is:

"Standalone APK supporting ARM64 Android devices and x86_64 emulators, with the on-device Gemma model distributed separately."

==================================================
36. DISTRIBUTION VALIDATION
==================================================

Before declaring the Release APK READY, validate:

1. Release APK builds successfully.
2. APK exists at the expected path.
3. APK is standalone/universal.
4. arm64-v8a native library exists.
5. x86_64 native library exists.
6. GGUF model is NOT inside the APK.
7. Manifest/package/application metadata is correct.
8. minSdk/targetSdk are correct.
9. APK structure passes aapt validation.
10. Signature passes apksigner validation.
11. V1/V2/V3/V4 configuration is correct.
12. Native ELF LOAD alignment is 0x4000 where required.
13. ZIP/native alignment passes zipalign validation.
14. ADB install passes.
15. APK installs by normal tap-to-install on a physical Android device.
16. ACE launches after normal installation.
17. No Application ClassNotFoundException occurs.
18. No native library loading failure occurs.
19. No immediate startup crash occurs.
20. The actual Release APK—not an old Debug APK—is tested.

==================================================
37. INSTALLATION COMPATIBILITY
==================================================

If installation fails:

DO NOT GUESS.

Capture the exact Android installation error.

Examples:

INSTALL_FAILED_UPDATE_INCOMPATIBLE
INSTALL_FAILED_VERSION_DOWNGRADE
INSTALL_FAILED_NO_MATCHING_ABIS
INSTALL_PARSE_FAILED_*
INSTALL_FAILED_INVALID_APK

Diagnose the exact cause.

For certificate mismatch:

Explain that an APK signed with a different certificate cannot update the existing installed package.

Do not incorrectly blame the APK architecture.

For ABI failure:

inspect packaged native libraries.

For parse/signing failure:

inspect APK structure and signing.

For version downgrade:

inspect versionCode/versionName/build configuration.

==================================================
38. REAL DEVICE COMPATIBILITY
==================================================

Do not claim ACE works on "any Android device."

Compatibility requires:

- supported Android version
- supported CPU ABI
- sufficient storage
- sufficient RAM for the local Gemma runtime
- required Android permissions
- required hardware capabilities
- compatible native runtime

A device may successfully INSTALL ACE but still be unable to RUN the local Gemma model because of memory constraints.

Therefore distinguish:

INSTALL COMPATIBILITY
from
RUNTIME COMPATIBILITY
from
MODEL COMPATIBILITY

==================================================
39. MEMORY-CONSTRAINED DEVICES
==================================================

The local Gemma model is approximately 3 GB.

Treat memory as a first-class constraint.

On low-memory devices:

- do not crash
- do not repeatedly retry model loading
- report model/runtime unavailable
- keep the rest of the app functional where possible
- expose a truthful state such as MODEL_PRESENT_NO_RUNTIME or ERROR

Never pretend the local brain is READY if it is not.

==================================================
40. FINAL RELEASE CHECKLIST
==================================================

Before saying:

"RELEASE READY"

verify all of the following:

BUILD
[ ] Release build succeeds
[ ] APK path correct
[ ] No unnecessary clean rebuild
[ ] No unexpected native rebuild

APK
[ ] Standalone APK
[ ] arm64-v8a
[ ] x86_64
[ ] GGUF excluded
[ ] Correct package
[ ] Correct version
[ ] minSdk 26
[ ] targetSdk 35

SIGNING
[ ] Release signing valid
[ ] V1 verified/configured
[ ] V2 verified
[ ] V3 verified
[ ] V4 verified/configured where applicable

NATIVE
[ ] libllama_jni.so present
[ ] arm64-v8a native library valid
[ ] x86_64 native library valid
[ ] 16 KB alignment verified
[ ] No ABI mismatch

INSTALLATION
[ ] aapt validation passes
[ ] apksigner validation passes
[ ] zipalign validation passes
[ ] ADB install passes
[ ] Physical tap-to-install passes
[ ] App launches normally

RUNTIME
[ ] No Application ClassNotFoundException
[ ] No immediate startup crash
[ ] Native runtime initializes correctly
[ ] Model resolution selects intended GGUF
[ ] Model failure is graceful
[ ] Voice flow works
[ ] Task execution works
[ ] Verification state is truthful

==================================================
41. REPORTING STANDARD
==================================================

After meaningful engineering work, report concisely:

IMPLEMENTED
- what changed

WHY
- root cause/problem

PERFORMANCE
- runtime/build/memory impact

MEMORY
- relevant memory implications

APK
- size/path/ABI impact

VALIDATION
- exact tests performed

RESULT
- PASS / PARTIAL / BLOCKED / FAILED

Do not claim tests were performed if they were not.

Do not claim physical-device validation if only an emulator was tested.

Do not claim model runtime success if only model file detection succeeded.

==================================================
42. ABSOLUTE RULE
==================================================

Never optimize by weakening truth.

Never report:

"SUCCESS"

unless the requested outcome is actually verified.

Never report:

"READY"

unless the relevant validation has actually passed.

Never silently substitute components.

Never hide failures.

Never create unnecessary duplicate architecture.

Never perform expensive builds without a reason.

Always prefer:

FAST
SMALL
MEMORY-EFFICIENT
DETERMINISTIC
OBSERVABLE
VERIFIABLE
TRUTHFUL

ACE must not merely appear intelligent.

ACE must reliably complete real tasks and know whether it actually succeeded.
