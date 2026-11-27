package dev.trooped.tvquickbars.camera

import android.os.Parcel
import android.os.Parcelable

/** Compact interactive action pill rendered inside a PiP window (e.g. Doorbell controls). */
data class PipAction(
    val id: String,
    val label: String
) : Parcelable {
    constructor(parcel: Parcel) : this(
        id = parcel.readString() ?: "",
        label = parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(label)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<PipAction> {
        override fun createFromParcel(parcel: Parcel): PipAction = PipAction(parcel)
        override fun newArray(size: Int): Array<PipAction?> = arrayOfNulls(size)
    }
}

/**
 * Data class representing the specifications for a Camera Picture-in-Picture (PiP) window.
 */
data class CameraPipSpec(
    val url: String = "",
    val authToken: String? = null,
    val entityId: String = "",
    val title: String = "",
    val widthDp: Int = 280,
    val heightDp: Int = 158,
    val marginDp: Int = 20,
    val showTitle: Boolean = true,
    val cornerPosition: String = "TOP_LEFT",
    val autoHideTimeout: Int = 30,
    val rtspProfile: RtspProfile = RtspProfile(),
    val slotKey: String = cornerPosition,
    val isGroup: Boolean = false,
    val groupSpecs: List<CameraPipSpec> = emptyList(),
    val groupLayout: String = "grid",
    val autoCloseOnSleep: Boolean = true,
    val snapshotUrl: String? = null,
    val fallbackMjpegUrl: String? = null,
    val borderColorHex: String? = null,
    val borderWidthDp: Int = 0,
    val cornerRadiusDp: Int = 8,
    val opacity: Float = 1.0f,
    val showLiveBadge: Boolean = true,
    val showTimestamp: Boolean = false,
    val showCountdownBar: Boolean = true,
    val isFullscreen: Boolean = false,
    val cid: String? = null,
    val actions: List<PipAction> = emptyList()
) : Parcelable {
    constructor(parcel: Parcel) : this(
        url = parcel.readString() ?: "",
        authToken = parcel.readString(),
        entityId = parcel.readString() ?: "",
        title = parcel.readString() ?: "",
        widthDp = parcel.readInt(),
        heightDp = parcel.readInt(),
        marginDp = parcel.readInt(),
        showTitle = parcel.readInt() == 1,
        cornerPosition = parcel.readString() ?: "TOP_LEFT",
        autoHideTimeout = parcel.readInt(),
        rtspProfile = parcel.readParcelable(RtspProfile::class.java.classLoader) ?: RtspProfile(),
        slotKey = parcel.readString() ?: "TOP_LEFT",
        isGroup = parcel.readInt() == 1,
        groupSpecs = mutableListOf<CameraPipSpec>().apply {
            parcel.readTypedList(this, CREATOR)
        },
        groupLayout = parcel.readString() ?: "grid",
        autoCloseOnSleep = parcel.readInt() != 0,
        snapshotUrl = parcel.readString(),
        fallbackMjpegUrl = parcel.readString(),
        borderColorHex = parcel.readString(),
        borderWidthDp = parcel.readInt(),
        cornerRadiusDp = parcel.readInt(),
        opacity = parcel.readFloat(),
        showLiveBadge = parcel.readInt() != 0,
        showTimestamp = parcel.readInt() != 0,
        showCountdownBar = parcel.readInt() != 0,
        isFullscreen = parcel.readInt() != 0,
        cid = parcel.readString(),
        actions = mutableListOf<PipAction>().apply {
            parcel.readTypedList(this, PipAction.CREATOR)
        }
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(url)
        parcel.writeString(authToken)
        parcel.writeString(entityId)
        parcel.writeString(title)
        parcel.writeInt(widthDp)
        parcel.writeInt(heightDp)
        parcel.writeInt(marginDp)
        parcel.writeInt(if (showTitle) 1 else 0)
        parcel.writeString(cornerPosition)
        parcel.writeInt(autoHideTimeout)
        parcel.writeParcelable(rtspProfile, flags)
        parcel.writeString(slotKey)
        parcel.writeInt(if (isGroup) 1 else 0)
        parcel.writeTypedList(groupSpecs)
        parcel.writeString(groupLayout)
        parcel.writeInt(if (autoCloseOnSleep) 1 else 0)
        parcel.writeString(snapshotUrl)
        parcel.writeString(fallbackMjpegUrl)
        parcel.writeString(borderColorHex)
        parcel.writeInt(borderWidthDp)
        parcel.writeInt(cornerRadiusDp)
        parcel.writeFloat(opacity)
        parcel.writeInt(if (showLiveBadge) 1 else 0)
        parcel.writeInt(if (showTimestamp) 1 else 0)
        parcel.writeInt(if (showCountdownBar) 1 else 0)
        parcel.writeInt(if (isFullscreen) 1 else 0)
        parcel.writeString(cid)
        parcel.writeTypedList(actions)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<CameraPipSpec> {
        override fun createFromParcel(parcel: Parcel): CameraPipSpec = CameraPipSpec(parcel)
        override fun newArray(size: Int): Array<CameraPipSpec?> = arrayOfNulls(size)
    }
}
