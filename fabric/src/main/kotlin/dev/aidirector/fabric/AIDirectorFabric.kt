package dev.aidirector.fabric

import dev.aidirector.AIDirector
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.bootstrap.EventWiring
import dev.aidirector.platform.CommonSensors
import dev.aidirector.platform.CommonServerActions
import dev.aidirector.sensors.SensorsHolder
import net.fabricmc.api.ModInitializer

class AIDirectorFabric : ModInitializer {
    override fun onInitialize() {
        AIDirector.log.info("AI Director (Fabric) {} starting", AIDirector.VERSION)
        ServerActionsHolder.install(CommonServerActions(serverSupplier = { EventWiring.serverRef.get() }))
        SensorsHolder.install(CommonSensors(serverSupplier = { EventWiring.serverRef.get() }))
        EventWiring.register()
    }
}
