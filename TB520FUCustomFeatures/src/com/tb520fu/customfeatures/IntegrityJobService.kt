/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.customfeatures

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Automatic keybox renewal (the Specter port), only scheduled while the
 * Specter switch is on. It runs while the keybox feature is enabled, fetches
 * the catalog and installs a fresh keybox through the system_server service;
 * the framework picks it up live, so after the first restart enabling the
 * feature nobody has to reboot again.
 */
class IntegrityJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        if (!Integrity.enabled(Integrity.SPECTER)) {
            cancel(applicationContext)
            return false
        }
        Thread {
            try {
                KeyboxRenewal.renew(
                    force = false,
                    autoRotate = Integrity.autoRotate(applicationContext),
                )
            } catch (t: Throwable) {
                // A failed run just waits for the next period.
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 52074
        private const val PERIOD_MS = 6 * 60 * 60 * 1000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, IntegrityJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
