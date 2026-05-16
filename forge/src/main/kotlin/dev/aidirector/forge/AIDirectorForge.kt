package dev.aidirector.forge

import dev.aidirector.AIDirector
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.bootstrap.EventWiring
import dev.aidirector.platform.CommonSensors
import dev.aidirector.platform.CommonServerActions
import dev.aidirector.sensors.SensorsHolder
import net.minecraftforge.fml.common.Mod

@Mod(AIDirector.MOD_ID)
class AIDirectorForge {
    init {
        ServerActionsHolder.install(CommonServerActions { EventWiring.serverRef.get() })
        SensorsHolder.install(CommonSensors { EventWiring.serverRef.get() })
        AIDirector.init()
    }
}
