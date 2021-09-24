package com.martafode.wifip2plibrary.common.listeners

import com.martafode.wifip2plibrary.common.messages.MessageWrapper

interface DataReceivedListener {
    fun onDataReceived(messageWrapper: MessageWrapper?)
}
