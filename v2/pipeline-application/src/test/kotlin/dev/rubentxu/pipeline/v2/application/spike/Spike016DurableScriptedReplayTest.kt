package dev.rubentxu.pipeline.v2.application.spike

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Random
import java.util.concurrent.TimeUnit

/** Test-only SPIKE-016 model. It is feasibility evidence, never production proof. */
@Timeout(10)
class Spike016DurableScriptedReplayTest {
    @TempDir lateinit var tempDir: Path
    @AfterEach fun tearDown() = Children.killAll()

    @Test fun `E1 runtime value controls Kotlin branch`() = runBlocking {
        val s = Session(tempDir.resolve("e1"), true)
        val result = Runtime("v1", s.journal(), s.executor()).run {
            if (sh("branch", "branch", true).trim() == "main") sh("deploy", "deploy") else "skipped"
        }
        assertEquals("deployed", result)
    }

    @Test fun `E2 replay uses deserialized bytes and new runtime and executor`() = runBlocking {
        val s = Session(tempDir.resolve("e2"), true)
        try { Runtime("v1", s.journal(), s.executor(), Cut.AFTER_RETURN).run { sh("branch", "branch", true); crashAfterReturn() } } catch (_: Crash) {}
        val decoded = Journal.decode(s.bytes())
        assertEquals(State.SUCCEEDED, decoded["v1|entry|branch||0"]?.state)
        val replay = Runtime("v1", s.journal(), s.executor())
        assertEquals("deployed", replay.run { if (sh("branch", "branch", true).trim() == "main") sh("deploy", "deploy") else "skipped" })
        assertEquals(1, s.launches("v1|entry|branch||0")); assertEquals(1, s.launches("v1|entry|deploy||0"))
        assertTrue(replay.replayed.any { "branch" in it })
    }

    @Test fun `launch instrumentation records every launch of the same operation id`() {
        val s = Session(tempDir.resolve("launch-events"), true)
        s.executor().launch("same-operation", "step")
        s.executor().launch("same-operation", "step")
        assertEquals(2, s.launches("same-operation"))
    }

    @Test fun `serialized journal permits only declared primitive wire fields and no coroutine payload`() = runBlocking {
        val s = Session(tempDir.resolve("wire"), true)
        Runtime("v1", s.journal(), s.executor()).run { sh("step", "step") }
        val bytes = s.bytes(); val roundTrip = Journal.decode(bytes)
        assertEquals(roundTrip.snapshot(), Journal.decode(roundTrip.bytes()).snapshot())
        listOf(WireJournal::class.java, WireOperation::class.java).flatMap { it.declaredFields.asIterable() }.filterNot { it.isSynthetic }.forEach {
            assertTrue(it.type == String::class.java || it.type == java.lang.Long::class.java || it.type == java.util.List::class.java, "wire field ${it.name} is ${it.type}")
        }
        val text = bytes.toString(StandardCharsets.ISO_8859_1)
        assertFalse(text.contains("continuation", true)); assertFalse(text.contains("coroutine", true)); assertFalse(text.contains("kotlinx", true))
    }

    @Test fun `E3 all crash cuts recover from bytes`() = runBlocking { Cut.entries.forEach { verifyCut(it, "matrix-$it") } }
    @Test fun `E3 randomized cuts are deterministic with diagnostic seed`() = runBlocking {
        val seed = 0x51A7E016L; val random = Random(seed)
        repeat(18) { n -> verifyCut(Cut.entries[random.nextInt(Cut.entries.size)], "seed=$seed iteration=$n") }
    }

    @Test fun `E4 source digest mismatch fails closed`() = runBlocking {
        val s = Session(tempDir.resolve("source"), true); Runtime("v1", s.journal(), s.executor()).run { sh("step", "step") }
        assertTrue(runCatching { Runtime("v2", s.journal(), s.executor()).run { sh("step", "step") } }.exceptionOrNull() is Compatibility)
        assertEquals(1, s.launches("v1|entry|step||0"), "source mismatch must fail before any additional effect launch")
        assertEquals(0, s.launches("v2|entry|step||0"), "source mismatch must not launch the incompatible operation")
    }
    @Test fun `input digest mismatch fails closed without relaunch`() = runBlocking {
        val s = Session(tempDir.resolve("input"), true); Runtime("v1", s.journal(), s.executor()).run { sh("step", "one") }
        assertTrue(runCatching { Runtime("v1", s.journal(), s.executor()).run { sh("step", "two") } }.exceptionOrNull() is Compatibility)
        assertEquals(1, s.launches("v1|entry|step||0"))
    }
    @Test fun `E5 loop identities are stable after bytes`() = runBlocking {
        val s = Session(tempDir.resolve("loop"), true)
        Runtime("v1", s.journal(), s.executor()).run { repeat(3) { i -> loop("items", i) { sh("step", "loop-$i") } } }
        val ids = s.journal().ids(); Runtime("v1", s.journal(), s.executor()).run { repeat(3) { i -> loop("items", i) { sh("step", "loop-$i") } } }
        assertEquals(3, ids.toSet().size); assertTrue(ids.all { "loop:items" in it })
    }
    @Test fun `E6 nested attempt scope identities are stable after bytes`() = runBlocking {
        val s = Session(tempDir.resolve("nested"), true); val block: suspend Scope.() -> Unit = { retry("deploy", 2) { scoped("x") { sh("step", "n-$it") } } }
        Runtime("v1", s.journal(), s.executor()).run(block); val ids = s.journal().ids(); Runtime("v1", s.journal(), s.executor()).run(block)
        assertEquals(2, ids.size); assertTrue(ids.any { "attempt:1/scope:x" in it }); assertTrue(ids.any { "attempt:2/scope:x" in it })
    }
    @Test fun `E7 typed failure rethrows from bytes without relaunch`() = runBlocking {
        val s = Session(tempDir.resolve("fail"), true); val responses = mapOf("fail" to Result.Fail("SCRIPT", "exit 9"))
        assertTrue(runCatching { Runtime("v1", s.journal(), s.executor(responses)).run { sh("step", "fail") } }.exceptionOrNull() is RecordedFailure)
        val replay = runCatching { Runtime("v1", s.journal(), s.executor(responses)).run { sh("step", "fail") } }.exceptionOrNull() as RecordedFailure
        assertEquals("SCRIPT", replay.kind); assertEquals("exit 9", replay.message); assertEquals(1, s.launches("v1|entry|step||0"))
    }

    // ---- Widened SPIKE-016: audited extensions E2a..E5c and negative N1..N6 ----

    @Test fun `E2a replayed value equals original with single launch`() = runBlocking {
        val s = Session(tempDir.resolve("e2a"), true)
        runCatching { Runtime("v1", s.journal(), s.executor(), Cut.AFTER_RETURN).run { sh("step", "step") } }
        val replay = Runtime("v1", s.journal(), s.executor())
        assertEquals("step-result", replay.run { sh("step", "step") })
        assertEquals(1, s.launches("v1|entry|step||0")); assertEquals(listOf("v1|entry|step||0"), replay.replayed)
    }
    @Test fun `E2b decode is idempotent across reads of the same bytes`() = runBlocking {
        val s = Session(tempDir.resolve("e2b"), true)
        Runtime("v1", s.journal(), s.executor()).run { sh("step", "step") }
        val bytes = s.bytes()
        assertEquals(Journal.decode(bytes).snapshot(), Journal.decode(bytes).snapshot())
    }
    @Test fun `E3a journal bytes decode cleanly at every crash cut`() = runBlocking {
        Cut.entries.forEach { cut ->
            val s = Session(tempDir.resolve("e3a-${cut.name.lowercase()}"), false)
            runCatching { Runtime("v1", s.journal(), s.executor(), cut).run { sh("step", "step") } }
            Journal.decode(s.bytes()) // must never throw: no torn state
        }
    }
    @Test fun `E4a source mismatch fails closed with an in-flight RUNNING entry`() = runBlocking {
        val s = Session(tempDir.resolve("e4a"), true)
        runCatching { Runtime("v1", s.journal(), s.executor(), Cut.WHILE_RUNNING).run { sh("step", "step") } }
        assertEquals(State.RUNNING, Journal.decode(s.bytes())["v1|entry|step||0"]?.state)
        assertTrue(runCatching { Runtime("v2", s.journal(), s.executor()).run { sh("step", "step") } }.exceptionOrNull() is Compatibility)
        assertEquals(1, s.launches("v1|entry|step||0")); assertEquals(0, s.launches("v2|entry|step||0"))
    }
    @Test fun `E5a nested loop identities are stable and distinct after bytes`() = runBlocking {
        val s = Session(tempDir.resolve("e5a"), true)
        val block: suspend Scope.() -> Unit = { repeat(2) { i -> loop("o", i) { repeat(2) { j -> loop("n", j) { sh("c-$i$j", "x") } } } } }
        Runtime("v1", s.journal(), s.executor()).run(block); val ids = s.journal().ids()
        val replay = Runtime("v1", s.journal(), s.executor()); replay.run(block)
        assertEquals(4, ids.toSet().size); assertEquals(4, replay.replayed.size)
        ids.forEach { assertEquals(1, s.launches(it)) }
    }
    @Test fun `E5b repeated identical calls in one scope get stable ordinals`() = runBlocking {
        val s = Session(tempDir.resolve("e5b"), true)
        val block: suspend Scope.() -> Unit = { sh("dup", "x"); sh("dup", "x") }
        Runtime("v1", s.journal(), s.executor()).run(block); val ids = s.journal().ids()
        val replay = Runtime("v1", s.journal(), s.executor()); replay.run(block)
        assertEquals(2, ids.size); assertTrue(ids.none { it == ids.first() } || ids[0] != ids[1])
        assertEquals(2, replay.replayed.size); ids.forEach { assertEquals(1, s.launches(it)) }
    }
    @Test fun `E5c retry attempt scopes compose with loop iteration identities`() = runBlocking {
        val s = Session(tempDir.resolve("e5c"), true)
        val block: suspend Scope.() -> Unit = { repeat(2) { i -> loop("L", i) { retry("R", 2) { k -> sh("a-$i-$k", "x") } } } }
        Runtime("v1", s.journal(), s.executor()).run(block); val ids = s.journal().ids()
        val replay = Runtime("v1", s.journal(), s.executor()); replay.run(block)
        assertEquals(4, ids.toSet().size); assertEquals(4, replay.replayed.size)
        assertTrue(ids.all { "attempt:1" in it || "attempt:2" in it })
    }
    @Test fun `N1 source mismatch on a SCHEDULED entry fails closed without launch`() = runBlocking {
        val s = Session(tempDir.resolve("n1"), false)
        runCatching { Runtime("v1", s.journal(), s.executor(), Cut.AFTER_SCHEDULE).run { sh("step", "step") } }
        assertEquals(0, s.launches("v1|entry|step||0"))
        assertTrue(runCatching { Runtime("v2", s.journal(), s.executor()).run { sh("step", "step") } }.exceptionOrNull() is Compatibility)
        assertEquals(0, s.launches("v2|entry|step||0"))
    }
    @Test fun `N2 adversarial scope-name collision fails closed via input digest`() = runBlocking {
        val s = Session(tempDir.resolve("n2"), true)
        Runtime("v1", s.journal(), s.executor()).run { scoped("a[0]/scope:b") { sh("x", "p1") } }
        assertTrue(runCatching { Runtime("v1", s.journal(), s.executor()).run { scoped("a[0]") { scoped("b") { sh("x", "p2") } } } }.exceptionOrNull() is Compatibility)
        assertEquals(1, s.launches("v1|entry|x|scope:a[0]/scope:b|0"), "aliased id must not launch twice; equal-input aliasing is an ADR-0066 production requirement")
    }
    @Test fun `N3 schema-version downgrade is refused by the older reader`() = runBlocking {
        val s = Session(tempDir.resolve("n3"), true)
        Runtime("v1", s.journal(), s.executor()).run { sh("step", "step") }
        val upgraded = s.bytes().copyOf(); upgraded[3] = 2 // big-endian header writeInt(1) -> 2
        assertTrue(runCatching { Journal.decode(upgraded) }.isFailure, "v1 reader must refuse v2 stream")
        run<Unit> { Journal.decode(s.bytes()) } // untouched stream still readable
    }
    @Test fun `N4 interrupted recovery records ordered terminal and replays typed without relaunch`() = runBlocking {
        val s = Session(tempDir.resolve("n4"), true); val id = "v1|entry|step||0"
        runCatching { Runtime("v1", s.journal(), s.executor(), Cut.WHILE_RUNNING).run { sh("step", "step") } }
        assertEquals(1, s.launches(id))
        val first = runCatching { Runtime("v1", s.journal(), s.executor(), recovery = Recovery.CANCELLED).run { sh("step", "step") } }.exceptionOrNull() as RecordedFailure
        assertEquals("PARENT_CANCELLED", first.kind)
        val e = s.journal()[id]!!; assertEquals(State.INTERRUPTED, e.state)
        assertEquals(listOf(State.SCHEDULED, State.RUNNING, State.RECONCILING, State.INTERRUPTED), e.transitions)
        val replay = runCatching { Runtime("v1", s.journal(), s.executor()).run { sh("step", "step") } }.exceptionOrNull() as RecordedFailure
        assertEquals("PARENT_CANCELLED", replay.kind); assertEquals(1, s.launches(id))
    }
    @Test fun `N5 parent cancellation propagates - subsequent operations never launch`() = runBlocking {
        val root = tempDir.resolve("n5"); val s = Session(root, true)
        val direct = Scope(Runtime("v1", s.journal(), s.executor()), emptyList(), mutableMapOf())
        direct.sh("a", "one")
        runCatching { Runtime("v1", s.journal(), s.executor(), Cut.WHILE_RUNNING).run { sh("a", "one"); sh("b", "two"); sh("c", "three") } }
        val err = runCatching { Runtime("v1", s.journal(), s.executor(), recovery = Recovery.CANCELLED).run { sh("a", "one"); sh("b", "two"); sh("c", "three") } }.exceptionOrNull() as RecordedFailure
        assertEquals("PARENT_CANCELLED", err.kind)
        assertEquals(State.SUCCEEDED, s.journal()["v1|entry|a||0"]?.state)
        assertEquals(State.INTERRUPTED, s.journal()["v1|entry|b||0"]?.state)
        assertEquals(0, s.launches("v1|entry|c||0"), "cancelled parent must not launch later operations")
    }
    @Test fun `N6 timeout-window recovery records TIMEOUT terminal without relaunch`() = runBlocking {
        val s = Session(tempDir.resolve("n6"), true); val id = "v1|entry|step||0"
        runCatching { Runtime("v1", s.journal(), s.executor(), Cut.WHILE_RUNNING).run { sh("step", "step") } }
        val err = runCatching { Runtime("v1", s.journal(), s.executor(), recovery = Recovery.TIMEOUT).run { sh("step", "step") } }.exceptionOrNull() as RecordedFailure
        assertEquals("TIMEOUT", err.kind)
        assertEquals(State.TIMEOUT, s.journal()[id]!!.state)
        assertEquals(1, s.launches(id)); assertEquals(1, s.journal()[id]!!.transitions.count { it == State.TIMEOUT })
    }

    private suspend fun verifyCut(cut: Cut, diagnostic: String) {
        val s = Session(tempDir.resolve(diagnostic.replace(Regex("[^a-zA-Z0-9]"), "_")), false); val id = "v1|entry|step||0"
        val failed = runCatching { Runtime("v1", s.journal(), s.executor(), cut).run { sh("step", "step") } }.exceptionOrNull()
        assertEquals(cut, (failed as? Crash)?.cut, diagnostic)
        val before = Journal.decode(s.bytes())[id]; assertEquals(cut.persisted, before?.state, diagnostic); assertEquals(cut.initial, s.launches(id), diagnostic)
        s.release(); Runtime("v1", s.journal(), s.executor()).run { sh("step", "step") }
        val after = s.journal()[id]!!; assertEquals(State.SUCCEEDED, after.state, diagnostic); assertEquals(cut.initial + cut.recovery, s.launches(id), diagnostic)
        if (cut == Cut.AFTER_LAUNCH || cut == Cut.WHILE_RUNNING) {
            assertEquals(State.RUNNING, before?.state, diagnostic)
            assertEquals(listOf(State.SCHEDULED, State.RUNNING, State.RECONCILING, State.SUCCEEDED), after.transitions, "$diagnostic persisted RUNNING -> RECONCILING -> terminal")
        }
    }
}

private enum class Cut(val persisted: State?, val initial: Int, val recovery: Int) { BEFORE(null,0,1), AFTER_SCHEDULE(State.SCHEDULED,0,1), AFTER_LAUNCH(State.RUNNING,1,0), WHILE_RUNNING(State.RUNNING,1,0), AFTER_TERMINAL(State.SUCCEEDED,1,0), AFTER_RETURN(State.SUCCEEDED,1,0) }
private enum class State { SCHEDULED, RUNNING, RECONCILING, SUCCEEDED, FAILED, INTERRUPTED, TIMEOUT }
private class Crash(val cut: Cut): RuntimeException(); private class Compatibility: RuntimeException(); private class RecordedFailure(val kind:String, message:String): RuntimeException(message)
private sealed interface Result { data class Value(val value:String):Result; data class Fail(val kind:String,val message:String):Result }
private data class Task(val pid:Long,val ready:String,val result:String)
private data class Entry(val id:String,val input:String,var state:State,var task:Task?=null,var value:String?=null,var failure:Result.Fail?=null,val transitions:MutableList<State> = mutableListOf(state))
private data class WireJournal(val definition:String?,val operations:List<WireOperation>)
private data class WireOperation(val id:String,val input:String,val state:String,val pid:Long?,val ready:String?,val result:String?,val value:String?,val kind:String?,val message:String?,val transitions:List<String>)

private class Journal private constructor(private var definition:String?, private val entries:LinkedHashMap<String,Entry>) {
    private var checkpoint:(()->Unit)?=null
    operator fun get(id:String)=entries[id]; fun ids()=entries.keys.toList()
    fun checkpoint(action:()->Unit)=apply { checkpoint=action; changed() }
    fun definition(value:String) { if (definition == null) definition=value else if(definition != value) throw Compatibility(); changed() }
    fun schedule(id:String,input:String)=Entry(id,input,State.SCHEDULED).also { entries[id]=it; changed() }
    fun running(e:Entry,t:Task) { transition(e,State.RUNNING); e.task=t; changed() }
    fun reconciling(e:Entry)=transition(e,State.RECONCILING)
    fun finish(e:Entry,r:Result) { when(r) { is Result.Value->{transition(e,State.SUCCEEDED);e.value=r.value}; is Result.Fail->{transition(e,State.FAILED);e.failure=r} }; changed() }
    fun interrupt(e:Entry,kind:String,msg:String) { transition(e,if(kind=="TIMEOUT")State.TIMEOUT else State.INTERRUPTED);e.failure=Result.Fail(kind,msg);changed() }
    private fun transition(e:Entry,s:State) { e.state=s;e.transitions+=s;changed() }; private fun changed(){checkpoint?.invoke()}
    fun snapshot()=WireJournal(definition,entries.values.map { e->WireOperation(e.id,e.input,e.state.name,e.task?.pid,e.task?.ready,e.task?.result,e.value,e.failure?.kind,e.failure?.message,e.transitions.map(State::name)) })
    fun bytes()=Codec.encode(snapshot())
    companion object { fun empty()=Journal(null,linkedMapOf()); fun decode(bytes:ByteArray):Journal { val s=Codec.decode(bytes); return Journal(s.definition,LinkedHashMap(s.operations.associate { o->o.id to Entry(o.id,o.input,State.valueOf(o.state),o.pid?.let{Task(it,checkNotNull(o.ready),checkNotNull(o.result))},o.value,o.kind?.let{Result.Fail(it,checkNotNull(o.message))},o.transitions.map(State::valueOf).toMutableList()) })) } }
}
private object Codec {
    fun encode(s:WireJournal)=ByteArrayOutputStream().use { b->DataOutputStream(b).use { o->o.writeInt(1);o.n(s.definition);o.writeInt(s.operations.size);s.operations.forEach { x->o.t(x.id);o.t(x.input);o.t(x.state);o.l(x.pid);o.n(x.ready);o.n(x.result);o.n(x.value);o.n(x.kind);o.n(x.message);o.writeInt(x.transitions.size);x.transitions.forEach { value -> o.t(value) } } };b.toByteArray() }
    fun decode(b:ByteArray)=DataInputStream(ByteArrayInputStream(b)).use { i->require(i.readInt()==1);val def=i.n();val ops=List(i.readInt()){WireOperation(i.t(),i.t(),i.t(),i.l(),i.n(),i.n(),i.n(),i.n(),i.n(),List(i.readInt()){i.t()})};require(i.available()==0);WireJournal(def,ops) }
    private fun DataOutputStream.t(s:String){val b=s.toByteArray();writeInt(b.size);write(b)};private fun DataInputStream.t():String{val n=readInt();require(n in 0..1000000);return readNBytes(n).toString(StandardCharsets.UTF_8)}
    private fun DataOutputStream.n(s:String?){writeBoolean(s!=null);if(s!=null)t(s)};private fun DataInputStream.n()=if(readBoolean())t() else null
    private fun DataOutputStream.l(v:Long?){writeBoolean(v!=null);if(v!=null)writeLong(v)};private fun DataInputStream.l()=if(readBoolean())readLong() else null
}
private class Session(private val root:Path,released:Boolean) {
    private val journal=root.resolve("journal.bin");private val release=root.resolve("release");init{Files.createDirectories(root);if(released)release()}
    fun journal():Journal { val j=if(Files.exists(journal))Journal.decode(Files.readAllBytes(journal))else Journal.empty();return j.checkpoint { Files.write(journal,j.bytes()) } }
    fun executor(responses:Map<String,Result> = emptyMap())=Executor(root,release,responses);fun bytes()=Files.readAllBytes(journal);fun release(){Files.writeString(release,"go")};fun launches(id:String)=Files.list(root.resolve("launches")).use { it.filter { p->p.fileName.toString().startsWith("${id.token()}-") }.count().toInt() }
}
private class Executor(private val root:Path,private val release:Path,private val responses:Map<String,Result>) {
    init{Files.createDirectories(root.resolve("launches"))};    fun launch(id:String,script:String):Task { val token=id.token();val ready=root.resolve("$token.ready");val result=root.resolve("$token.result");val done=root.resolve("$token.done");Files.createTempFile(root.resolve("launches"),"$token-",".launch");val payload=Base64.getUrlEncoder().encodeToString(encode(response(script)).toByteArray());val cmd="touch \"\$1\"; while [ ! -f \"\$2\" ]; do sleep 0.01; done; printf '%s' \"\$3\" > \"\$4\"; touch \"\$5\"";val p=ProcessBuilder("setsid","sh","-c",cmd,"sh",ready.toString(),release.toString(),payload,result.toString(),done.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();Children.add(p);return Task(p.pid(),ready.toString(),result.toString()) }
    fun ready(t:Task)=await(Path.of(t.ready),"ready");fun allowFinish(){Files.writeString(release,"go")};fun await(t:Task)=decode(Files.readString(awaitDone(Path.of(t.result),System.nanoTime()+TimeUnit.SECONDS.toNanos(3))));fun reconcile(t:Task):Result = await(t)
    private fun await(path:Path,what:String):Path {val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!Files.exists(path)){check(System.nanoTime()<deadline){"timeout polling $what"};Thread.yield()};return path};private fun awaitDone(result:Path,deadline:Long):Path{val done=result.parent?.resolve(result.fileName.toString().replace(".result",".done"))?:result;while(!Files.exists(done)){check(System.nanoTime()<deadline){"timeout polling done"};Thread.yield()};return result}private fun response(s:String)=responses[s]?:Result.Value(when(s){"branch"->"main\n";"deploy"->"deployed";else->"$s-result"});private fun encode(r:Result)=when(r){is Result.Value->"V:${r.value}";is Result.Fail->"F:${r.kind}:${r.message}"};private fun decode(s:String)=String(Base64.getUrlDecoder().decode(s)).split(":",limit=3).let{if(it[0]=="V")Result.Value(it[1])else Result.Fail(it[1],it[2])}
}
private object Children { private val processes=mutableListOf<Process>();fun add(p:Process){processes+=p};fun killAll(){processes.toList().forEach {p->if(p.isAlive)ProcessBuilder("kill","-KILL","-${p.pid()}").start().waitFor(1,TimeUnit.SECONDS);p.destroyForcibly();p.waitFor(1,TimeUnit.SECONDS)};processes.clear()} }
private class Runtime(private val digest:String,private val journal:Journal,private val executor:Executor,private val cut:Cut?=null,private val recovery:Recovery=Recovery.NONE) {
    val replayed=mutableListOf<String>();suspend fun <T> run(block:suspend Scope.()->T):T{journal.definition(digest);return Scope(this, emptyList(), mutableMapOf()).block()}
    suspend fun invoke(call:String,script:String,stdout:Boolean,path:List<String>,ordinal:Int):String {val id=listOf(digest,"entry",call,path.joinToString("/"),ordinal).joinToString("|");val input="$script|$stdout";val old=journal[id];if(old!=null){if(old.input!=input)throw Compatibility();return resume(old,id)};crash(Cut.BEFORE);val e=journal.schedule(id,input);crash(Cut.AFTER_SCHEDULE);journal.running(e,executor.launch(id,script));crash(Cut.AFTER_LAUNCH);if(cut==Cut.WHILE_RUNNING)executor.ready(checkNotNull(e.task));crash(Cut.WHILE_RUNNING);if(cut==Cut.AFTER_TERMINAL||cut==Cut.AFTER_RETURN){executor.ready(checkNotNull(e.task));executor.allowFinish()};journal.finish(e,executor.await(checkNotNull(e.task)));crash(Cut.AFTER_TERMINAL);val value=materialize(e,id,false);crash(Cut.AFTER_RETURN);return value}
    fun afterReturn()=crash(Cut.AFTER_RETURN);private fun resume(e:Entry,id:String)=when(e.state){State.SCHEDULED->{journal.running(e,executor.launch(id,e.input.substringBefore('|')));journal.finish(e,executor.await(checkNotNull(e.task)));materialize(e,id,false)};State.RUNNING->{journal.reconciling(e);if(recovery==Recovery.NONE){journal.finish(e,executor.reconcile(checkNotNull(e.task)))}else{journal.interrupt(e,if(recovery==Recovery.CANCELLED)"PARENT_CANCELLED" else "TIMEOUT","recovered $recovery")};materialize(e,id)};else->materialize(e,id)};private fun materialize(e:Entry,id:String,replay:Boolean=true)=when(e.state){State.SUCCEEDED->e.value.also{if(replay)replayed+=id}!!;State.FAILED,State.INTERRUPTED,State.TIMEOUT->{if(replay)replayed+=id;throw RecordedFailure(e.failure!!.kind,e.failure!!.message)};else->error("not terminal")};private fun crash(at:Cut){if(cut==at)throw Crash(at)}
}
private enum class Recovery { NONE, CANCELLED, TIMEOUT }
private class Scope(private val runtime:Runtime,private val path:List<String>,private val ordinals:MutableMap<String,Int>){suspend fun sh(call:String,script:String,stdout:Boolean=false):String{val key="$call|${path.joinToString("/")}";val o=ordinals.getOrDefault(key,0);ordinals[key]=o+1;return runtime.invoke(call,script,stdout,path,o)};suspend fun crashAfterReturn()=runtime.afterReturn();suspend fun <T> loop(name:String,i:Int,body:suspend Scope.()->T):T=Scope(runtime,path+"loop:$name[$i]",ordinals).body();suspend fun <T> scoped(n:String,body:suspend Scope.()->T):T=Scope(runtime,path+"scope:$n",ordinals).body();suspend fun retry(n:String,count:Int,body:suspend Scope.(Int)->Unit){repeat(count){i->Scope(runtime,path+"retry:$n"+"attempt:${i+1}",ordinals).body(i+1)}}}
private fun String.token()=Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray())
