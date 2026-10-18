package com.b570.pilot

import android.app.Application
import com.b570.pilot.data.CarRepository

/** Application：持有唯一的 [CarRepository]（連線與資料在畫面旋轉／切換間都保持）。 */
class B570Application : Application() {
    lateinit var repository: CarRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = CarRepository(this)
    }
}
