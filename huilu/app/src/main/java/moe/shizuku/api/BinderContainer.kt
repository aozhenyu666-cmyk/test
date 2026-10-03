package moe.shizuku.api

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/**
 * Shizuku 服务端把 binder 包在这个 Parcelable 里发过来，类名必须与 Shizuku-API 中的一致
 * （moe.shizuku.api.BinderContainer）才能被反序列化。格式与 Shizuku-API 13 相同。
 */
class BinderContainer(@JvmField val binder: IBinder?) : Parcelable {
    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeStrongBinder(binder)

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<BinderContainer> {
            override fun createFromParcel(source: Parcel) = BinderContainer(source.readStrongBinder())
            override fun newArray(size: Int) = arrayOfNulls<BinderContainer>(size)
        }
    }
}
