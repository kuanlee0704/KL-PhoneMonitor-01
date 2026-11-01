package com.example.phonemonitor

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

class SampleJob : JobService() {
    override fun onStartJob(p: JobParameters?): Boolean {
        Thread { Recorder.record(this); jobFinished(p, false) }.start()
        return true
    }
    override fun onStopJob(p: JobParameters?) = false

    companion object {
        private const val ID = 42
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val info = JobInfo.Builder(ID, ComponentName(ctx, SampleJob::class.java))
                .setPeriodic(15 * 60_000L).setPersisted(true).build()
            js.schedule(info)
        }
        fun cancel(ctx: Context) {
            (ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(ID)
        }
    }
}
