package com.perppilot.app

import android.app.Application
import com.perppilot.app.work.Scheduler

class PerpPilotApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.notifier.createChannels()
        Scheduler.sync(this, graph.settings.current)
    }
}

val android.content.Context.graph: AppGraph get() = (applicationContext as PerpPilotApp).graph
