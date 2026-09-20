package com.bloodsucker.home

import android.app.Application
import com.bloodsucker.home.data.HomeRepository

class BloodsuckerApp : Application() {
    val repository by lazy { HomeRepository(this) }
}
