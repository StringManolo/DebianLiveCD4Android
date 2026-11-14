package io.github.stringmanolo.DebianLiveCD4Android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/** Periodic background check (every ~30 min, survives reboots): scheduled-wipe conditions and automatic updates. */
class MaintenanceJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { Maintenance.runOnce(applicationContext) } finally { jobFinished(params, false) }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}

object Maintenance {
    private const val JOB_ID = 4242

    fun schedule(ctx: Context) {
        try {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.allPendingJobs.any { it.id == JOB_ID }) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, MaintenanceJob::class.java))
                    .setPeriodic(30 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
            )
        } catch (e: Exception) {
            android.util.Log.w("Maintenance", "cannot schedule: $e")
        }
    }

    fun runOnce(ctx: Context) {
        val reason = WipeManager.checkConditions(ctx)
        if (reason != null) {
            startWipe(ctx, reason)
            return
        }
        Updater.autoCheckIfDue(ctx)
    }

    fun startWipe(ctx: Context, reason: String) {
        val i = Intent(ctx, TerminalService::class.java).setAction(TerminalService.ACTION_WIPE).putExtra("reason", reason)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }
}
