package com.mkdevelopers.data.models

import com.mkdevelopers.util.Constants

data class RoundDrawInfo(
    val data: List<String>
): BaseModel(Constants.TYPE_CUR_ROUND_DRAW_INFO)
