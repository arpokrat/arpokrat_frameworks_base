package grapheneos.test.common

import android.os.DeadObjectException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class DeadObjectExceptionRetryRule(private val retryCount: Int) : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                var exception: DeadObjectException? = null
                repeat(retryCount) { _ ->
                    try {
                        base.evaluate()
                        return
                    } catch (e: DeadObjectException) {
                        exception = e
                    }
                }
                exception?.let { throw it }
            }
        }
    }
}
