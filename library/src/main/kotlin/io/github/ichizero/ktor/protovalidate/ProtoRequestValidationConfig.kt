package io.github.ichizero.ktor.protovalidate

import build.buf.protovalidate.Config
import build.buf.protovalidate.Validator

/**
 * A config for [ProtoRequestValidation] plugin.
 */
class ProtoRequestValidationConfig {
    internal var config: Config? = null

    /**
     * Configures [Validator] with [Config].
     */
    fun configure(config: Config) {
        this.config = config
    }
}
