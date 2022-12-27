/*
 * Copyright (C) 2022 Jorge Ruesga
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ruesga.dash.wallclock

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.apache.tika.Tika
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.io.path.exists


class WallClockServer(private val port: Int, private val root: Path) {
    private lateinit var server: HttpServer
    private val tika = Tika()

    fun start() {
        server = HttpServer.create(InetSocketAddress(port), 0)
        server.createContext("/", WallClockServerHandler())
        server.executor = Executors.newFixedThreadPool(5)
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    private inner class WallClockServerHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val request = exchange.requestURI.path.substring(1)
            val resource = root.resolve(request)
            response(exchange, resource)
        }

        private fun response(exchange: HttpExchange, resource: Path) {
            val rootFile = root.toAbsolutePath().normalize().toString()
            val resourceFile = resource.toAbsolutePath().normalize().toString()
            if (resourceFile.startsWith(rootFile).not()) {
                error(exchange, BAD_REQUEST)
                return
            }
            if (resource.exists().not()) {
                error(exchange, NOT_FOUND)
                return
            }

            val contentType: String = tika.detect(resource)
            val response = resource.toFile().readBytes()
            success(exchange, response, contentType)
        }

        private fun error(exchange: HttpExchange, error: Int) {
            exchange.sendResponseHeaders(error, 0)
            exchange.responseBody.close()
        }

        private fun success(exchange: HttpExchange, response: ByteArray, contentType: String) {
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { os ->
                os.write(response)
            }
        }
    }

    private companion object {
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
    }
}

