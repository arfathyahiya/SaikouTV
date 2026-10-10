package ani.saikou.settings

import android.content.Context
import ani.saikou.loadData
import ani.saikou.saveData
import java.io.Serializable

data class PlayerSettings(
    //Video
    var videoInfo: Boolean = true,
    var defaultSpeed: Int = 5,
    var cursedSpeeds: Boolean = false,
    var resize: Int = 0,

    //Subtitles
    var subtitles: Boolean = true,
    var primaryColor: Int = 4,
    var secondaryColor: Int = 0,
    var outline: Int = 0,
    var subBackground: Int = 0,
    var subWindow: Int = 0,
    var font: Int = 0,
    var fontSize: Int = 20,
    var locale: Int = 2,

    //TimeStamps
    var timeStampsEnabled: Boolean = true,
    var useProxyForTimeStamps: Boolean = true,
    var showTimeStampButton: Boolean = true,

    //Auto
    var autoSkipOPED: Boolean = false,
    var autoPlay: Boolean = true,
    var autoSkipFiller: Boolean = false,

    //Update Progress
    var askIndividual: Boolean = true,
    var updateForH: Boolean = false,
    var watchPercentage: Float = 0.8f,

    //Behaviour
    var alwaysContinue: Boolean = true,
    var focusPause: Boolean = true,
    var gestures: Boolean = true,
    var doubleTap: Boolean = true,
    var seekTime: Int = 10,
    var skipTime: Int = 85,

    //Other
    var cast: Boolean = false,
    var pip: Boolean = true
) : Serializable {
    companion object {
        val CURSED_SPEEDS = arrayOf(
            0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f
        )
        val CURSED_SPEED_LABELS = arrayOf(
            "0.25x", "0.5x", "0.75x", "1x", "1.25x", "1.5x", "1.75x", "2x", "3x"
        )
    }
}

private const val CURSED_SPEED_LIST_VERSION_KEY = "cursed_speed_list_version"
private const val CURRENT_CURSED_SPEED_LIST_VERSION = 1

fun PlayerSettings.migrateCursedSpeedList(context: Context): Boolean {
    val storedVersion = loadData<Int>(CURSED_SPEED_LIST_VERSION_KEY, context, toast = false) ?: 0
    if (storedVersion >= CURRENT_CURSED_SPEED_LIST_VERSION) return false

    if (cursedSpeeds) defaultSpeed = PlayerSettings.CURSED_SPEEDS.indexOf(1f)
    saveData(CURSED_SPEED_LIST_VERSION_KEY, CURRENT_CURSED_SPEED_LIST_VERSION, context)
    return true
}
