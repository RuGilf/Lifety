package ru.lifety.core.platform

import ru.lifety.core.model.HealthSnapshot
import ru.lifety.core.model.HealthApiSample

/** Implemented by the iOS host; credentials never enter the agent's file workspace. */
interface IosCredentialStorage {
    fun read(): IosSecretResult
    fun write(value: String): Boolean
}
data class IosSecretResult(val value: String?, val error: String?)

interface IosSectionLockStorage : IosCredentialStorage {
    fun createSalt(): IosSecretResult
    fun derivePin(pin: String, salt: String): IosSecretResult
}

/** HealthKit deliberately does not reveal whether a read permission was denied. */
interface IosHealthSource {
    val available: Boolean
    fun requestAccess(sleep: Boolean, steps: Boolean, completion: (String?) -> Unit)
    fun readDays(sleep: Boolean, steps: Boolean, days: Int, completion: (List<HealthSnapshot>?, String?) -> Unit)
    fun cancelRead()
}

interface IosMetricSource {
    val available: Boolean
    fun requestAccess(keys: List<String>, completion: (String?) -> Unit)
    fun readDays(keys: List<String>, completion: (List<HealthApiSample>?, String?) -> Unit)
    fun cancel()
}

object IosServices {
    var personPhotos: IosPersonPhotoPicker? = null
    var arcNavigation: IosArcNavigationFactory? = null
    var credentials: IosCredentialStorage? = null
    var health: IosHealthSource? = null
    var sectionLocks: IosSectionLockStorage? = null
    var healthMetrics: IosMetricSource? = null
}
