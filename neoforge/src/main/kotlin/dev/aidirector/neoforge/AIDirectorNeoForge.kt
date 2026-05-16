package dev.aidirector.neoforge

import dev.aidirector.AIDirector
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.bootstrap.EventWiring
import dev.aidirector.platform.CommonSensors
import dev.aidirector.platform.CommonServerActions
import dev.aidirector.sensors.SensorsHolder
import net.neoforged.fml.common.Mod

@Mod(AIDirector.MOD_ID)
class AIDirectorNeoForge {
    init {
        AIDirector.log.info("AI Director (NeoForge) {} starting", AIDirector.VERSION)
        ServerActionsHolder.install(CommonServerActions(serverSupplier = { EventWiring.serverRef.get() }))
        SensorsHolder.install(CommonSensors(serverSupplier = { EventWiring.serverRef.get() }))
        EventWiring.register()
    }
}
