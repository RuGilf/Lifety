package ru.lifety.core.platform

import platform.UIKit.UIViewController

interface IosPersonPhotoRequest { fun cancel() }
interface IosPersonPhotoPicker {
    /** One resized JPEG as Base64; null/null means cancellation. Never requests full library access. */
    fun pick(presenter: UIViewController, completion: (String?, String?) -> Unit): IosPersonPhotoRequest
}
