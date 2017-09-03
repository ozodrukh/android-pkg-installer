package com.ozodrukh.android.pkg.installer

/**
 * @author Ozodrukh
 */

object Timber {

    private val trees: ArrayList<Tree> = arrayListOf<Tree>()

    fun plant(tree: Tree) {
        trees += tree
    }

    fun d(tag: String, message: String, vararg args: Any = emptyArray()) {
        trees.forEach { it.log(LogLevel.DEBUG, tag, message, null, *args) }
    }

    fun i(tag: String, message: String, vararg args: Any = emptyArray()) {
        trees.forEach { it.log(LogLevel.INFO, tag, message, null, *args) }
    }

    fun e(tag: String, error: Throwable?, message: String, vararg args: Any = emptyArray()) {
        trees.forEach { it.log(LogLevel.ERROR, tag, message, error, *args) }
    }

    enum class LogLevel {
        DEBUG,
        INFO,
        ERROR;

        internal open fun print(message: String) =
                System.out.println("$name -- $message")
    }

    abstract class Tree {
        abstract fun log(level: LogLevel, tag: String, message: String, error: Throwable?, vararg args: Any)
    }

    class DebugTree : Tree() {
        override fun log(level: LogLevel, tag: String, message: String, error: Throwable?, vararg args: Any) {
            level.print("$tag: ${String.format(message, args)}")
            error?.let { level.print(it.message!!) }
        }
    }
}

