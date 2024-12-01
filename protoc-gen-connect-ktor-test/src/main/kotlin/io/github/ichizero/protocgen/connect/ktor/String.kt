package io.github.ichizero.protocgen.connect.ktor

internal fun String.upperCamelCase(): String = replaceFirstChar { char -> char.uppercaseChar() }

internal fun String.lowerCamelCase(): String = replaceFirstChar { char -> char.lowercaseChar() }

internal fun String.packageToDirectory(): String {
    val dir = replace('.', '/')
    if (get(0) == '/') {
        return dir.substring(1)
    }
    return dir
}

internal fun String.sanitizeKdoc(): String = this
    // Remove trailing whitespace on each line.
    // ref.) https://github.com/square/kotlinpoet/issues/887
    .replace("[^\\S\n]+\n".toRegex(), "\n")
    .replace("\\s+$".toRegex(), "")
    .replace("\\*/".toRegex(), "&#42;/")
    .replace("/\\*".toRegex(), "/&#42;")
    .replace("""[""", "&#91;")
    .replace("""]""", "&#93;")
    .replace("@", "&#64;")
