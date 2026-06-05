package com.example.pa2.ui.theme

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.pa2.detector.Precision

/**
 * Holds shared, observable state across the three fragments
 * (Live, Dashboard, Export). The activity owns this VM.
 */
class SharedViewModel : ViewModel() {

    data class TargetState(val classId: Int, val label: String) {
        companion object { val NONE = TargetState(-1, "") }
    }

    private val _precision = MutableLiveData(Precision.FP32)
    val precision: LiveData<Precision> = _precision

    private val _target = MutableLiveData(TargetState.NONE)
    val target: LiveData<TargetState> = _target

    private val _lastVoiceTranscript = MutableLiveData("")
    val lastVoiceTranscript: LiveData<String> = _lastVoiceTranscript

    private val _statusMessage = MutableLiveData("")
    val statusMessage: LiveData<String> = _statusMessage

    fun setPrecision(p: Precision) { _precision.postValue(p) }
    fun setTarget(classId: Int, label: String) { _target.postValue(TargetState(classId, label)) }
    fun clearTarget() { _target.postValue(TargetState.NONE) }
    fun setVoiceTranscript(s: String) { _lastVoiceTranscript.postValue(s) }
    fun setStatus(s: String) { _statusMessage.postValue(s) }
}
