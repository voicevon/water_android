package com.water.von.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.water.von.data.LogEntry
import com.water.von.data.LogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史日志查询页面 ViewModel
 * 负责本地沙盒日志流的检索、筛选与异步加载
 */
class LogsViewModel(application: Application) : AndroidViewModel(application) {
    private val logManager = LogManager.getInstance(application)

    private val _selectedDate = MutableStateFlow("")
    val selectedDate: StateFlow<String> = _selectedDate

    private val _searchKeyword = MutableStateFlow("")
    val searchKeyword: StateFlow<String> = _searchKeyword

    private val _logsList = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsList: StateFlow<List<LogEntry>> = _logsList

    init {
        // 默认初始化为今天
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        setDateAndLoad(today)
    }

    /**
     * 设置日期并加载数据
     * @param dateStr 格式为 yyyyMMdd
     */
    fun setDateAndLoad(dateStr: String) {
        _selectedDate.value = dateStr
        loadLogs()
    }

    /**
     * 设置搜索关键字
     */
    fun setKeyword(keyword: String) {
        _searchKeyword.value = keyword
        loadLogs()
    }

    /**
     * 重新从本地文件系统加载日志，并进行关键字过滤
     */
    fun loadLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val date = _selectedDate.value
            val keyword = _searchKeyword.value
            val allLogs = logManager.readLogs(date)
            
            _logsList.value = if (keyword.isEmpty()) {
                allLogs
            } else {
                allLogs.filter { it.message.contains(keyword, ignoreCase = true) }
            }
        }
    }

    /**
     * 根据相对路径获取绝对图片文件对象
     */
    fun getPhotoFile(relativeImagePath: String) = logManager.getPhotoFile(relativeImagePath)
}
