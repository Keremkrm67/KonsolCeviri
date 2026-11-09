package com.playceviri.tv.worker

import android.content.Context
import java.io.File

/** Ana süreç ↔ işçi süreci (:isci) arasındaki mesaj anahtarları. Görüntü dosya ile taşınır. */
object WorkerProtocol {
    const val MSG_OCR = 1
    const val MSG_RESULT = 2
    const val MSG_TEST = 3
    const val MSG_TRANSLATE = 4
    const val MSG_WARMUP = 5

    const val K_ID = "id"
    const val K_W = "w"
    const val K_H = "h"
    const val K_PREP = "onisleme"
    const val K_OK = "ok"
    const val K_ERROR = "hata"
    const val K_LINES = "satirlar"
    const val K_CONFS = "guvenler"
    const val K_BOXES = "kutular"
    const val K_OCR_MS = "ocrMs"
    const val K_TEXT = "metin"
    const val K_TARGET = "hedef"
    const val K_SRC = "kaynak"
    const val K_TR_MODE = "cevirModu"
    const val K_DEEPL = "deepl"
    const val K_TERMS = "terimler"
    const val K_TRANSLATED = "ceviri"
    const val K_ENGINE = "motor"
    const val K_TR_MS = "trMs"
    const val K_STEP = "adim"
    const val K_ORIGINAL = "orijinal"
    const val K_STATUS = "durum"

    fun imageFile(c: Context) = File(c.applicationContext.cacheDir, "kirpinti.raw")
}
