package io.github.enoky.androidtts

import android.content.Context
import kotlin.concurrent.thread

/**
 * The one download that runs at a time, of the model or of the voices, and how far it got, for
 * the settings screen to show. It runs on its own thread, so it carries on while the screen is
 * rotated or left; both downloads pick up where they stopped when started again.
 */
object Downloads {
    enum class Job { MODEL, VOICES }

    sealed class State {
        object Idle : State()
        class Running(val job: Job, val detail: String?, val done: Long, val total: Long?) : State()
        class Done(val message: String) : State()
        class Failed(val job: Job, val message: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set

    @Volatile private var cancelled = false

    /** The download running, if any. */
    val running: Job? get() = (state as? State.Running)?.job

    /** Download Phonon 31m and install it in place of the current checkpoint. */
    fun downloadModel(context: Context) = start(context, Job.MODEL) { app ->
        Model.install(app) { into ->
            ModelDownload.fetch(into, ::isCancelled) { file, done, total -> report(Job.MODEL, file, done, total) }
        }
        Model.await(app)
        null
    }

    /** Download the catalog voices the installed model speaks and reload it with them. */
    fun downloadVoices(context: Context) = start(context, Job.VOICES) { app ->
        VoiceDownload.run(app, ::isCancelled) { detail, done, total -> report(Job.VOICES, detail, done, total) }
    }

    /** Stop the running download. What was installed before stays. */
    fun cancel() {
        cancelled = true
    }

    private fun isCancelled() = cancelled

    @Synchronized
    private fun start(context: Context, job: Job, work: (Context) -> String?) {
        if (state is State.Running) return
        cancelled = false
        state = State.Running(job, null, 0, null)
        val app = context.applicationContext
        thread(name = "download-${job.name.lowercase()}") {
            state = try {
                work(app)?.let { State.Done(it) } ?: State.Idle
            } catch (t: Throwable) {
                // Throwable, not Exception: anything left uncaught would leave the state Running.
                if (cancelled) State.Idle else State.Failed(job, t.message ?: t.toString())
            }
        }
    }

    private fun report(job: Job, detail: String?, done: Long, total: Long?) {
        state = State.Running(job, detail, done, total)
    }
}
