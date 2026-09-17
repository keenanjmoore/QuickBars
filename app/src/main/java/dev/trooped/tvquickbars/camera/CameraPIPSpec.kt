package dev.trooped.tvquickbars.camera

import android.os.Parcel
import android.os.Parcelable

/**
 * Data class representing the specifications for a Camera Picture-in-Picture (PIP) window.
 * This class is Parcelable, allowing it to be passed between components, such as Activities or Services.
 *
 * @property url The URL of the camera stream.
 * @property authToken Optional authentication token for accessing the camera stream.
 * @property entityId The unique identifier for the camera entity.
 * @property title The title to be displayed for the PIP window.
 * @property widthDp The width of the PIP window in density-independent pixels (dp).
 * @property heightDp The height of the PIP window in density-independent pixels (dp).
 * @property marginDp The margin around the PIP window in density-independent pixels (dp).
 * @property showTitle A boolean indicating whether to display the title in the PIP window.
 * @property cornerPosition The initial corner position of the PIP window on the screen.
 *                          Defaults to "TOP_LEFT". Other possible values: "TOP_RIGHT",
 *                          "BOTTOM_LEFT", "BOTTOM_RIGHT".
 * @property autoHideTimeout The duration in seconds after which the PIP window should automatically
 *                           hide if there's no interaction. Defaults to 30 seconds.
 * @property rtspProfile Advanced streaming profile parameters for RTSP feeds.
 * @property slotKey Unique identifier for the window slot (defaults to cornerPosition).
 * @property isGroup True if this spec represents a multi-camera combined dock/grid.
 * @property groupSpecs Sub-camera specifications when rendering in multi-camera mode.
 * @property groupLayout Layout format for multi-camera mode: "grid", "side_by_side", "stacked".
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
    val groupLayout: String = "grid"
) : Parcelable {
    constructor(parcel: Parcel) : this(
        url = parcel.readString() ?: "",
        authToken = parcel.readString(), // Fixed: Preserves null token cleanly
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
        groupLayout = parcel.readString() ?: "grid"
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
        // Fixed critical bug: writeParcelable instead of readParcelable
        parcel.writeParcelable(rtspProfile, flags)
        parcel.writeString(slotKey)
        parcel.writeInt(if (isGroup) 1 else 0)
        parcel.writeTypedList(groupSpecs)
        parcel.writeString(groupLayout)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<CameraPipSpec> {
        override fun createFromParcel(parcel: Parcel): CameraPipSpec = CameraPipSpec(parcel)
        override fun newArray(size: Int): Array<CameraPipSpec?> = arrayOfNulls(size)
    }
}