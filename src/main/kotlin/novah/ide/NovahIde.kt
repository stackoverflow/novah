/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah.ide

import org.eclipse.lsp4j.launch.LSPLauncher
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.Logger

object NovahIde {

    fun run(verbose: Boolean) {
        LogManager.getLogManager().reset()
        val globalLogger: Logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME)
        globalLogger.level = Level.OFF
        
        val server = NovahServer(verbose)
        val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out)

        val client = launcher.remoteProxy
        server.connect(client)

        val fut = launcher.startListening()
        fut.get()
    }
}