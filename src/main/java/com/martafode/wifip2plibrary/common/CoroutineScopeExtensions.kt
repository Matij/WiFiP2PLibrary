package com.martafode.wifip2plibrary.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun <P, R> CoroutineScope.executeAsyncTask(
    params: Array<P>,
    onPreExecute: () -> Unit = {},
    doInBackground: (params: Array<P>?) -> R,
    onPostExecute: (R) -> Unit = {}
) = launch {
    onPreExecute() // runs in Main Thread
    val result = withContext(Dispatchers.IO) {
        doInBackground(params) // runs in background thread without blocking the Main Thread
    }
    onPostExecute(result) // runs in Main Thread
}
