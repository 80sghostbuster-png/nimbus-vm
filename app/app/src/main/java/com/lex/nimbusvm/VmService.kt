package com.lex.nimbusvm

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Skeleton for Phase 2: a long-running VM service. Phase 1 boots from DebugActivity. */
class VmService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
