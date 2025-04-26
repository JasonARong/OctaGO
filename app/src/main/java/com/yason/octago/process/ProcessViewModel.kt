package com.yason.octago.process

import androidx.lifecycle.ViewModel

class ProcessViewModel : ViewModel(){
    private var _imagePaths: List<String> = emptyList()
    val imagePaths get() = _imagePaths

    fun setImagePaths(paths: List<String>) {
        _imagePaths = paths
    }
}