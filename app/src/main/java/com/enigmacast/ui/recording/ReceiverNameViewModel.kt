package com.enigmacast.ui.recording

import androidx.lifecycle.ViewModel
import com.enigmacast.live.ReceiverNames
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ReceiverNameViewModel @Inject constructor(names: ReceiverNames) : ViewModel() {
    val active = names.active
}
