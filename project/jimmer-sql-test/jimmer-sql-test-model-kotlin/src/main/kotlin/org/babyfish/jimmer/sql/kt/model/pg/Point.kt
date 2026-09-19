package org.babyfish.jimmer.sql.kt.model.pg

import com.fasterxml.jackson.annotation.JsonProperty
import org.babyfish.jimmer.sql.Serialized

@Serialized
data class Point(

    @param:JsonProperty("_x")
    @field:JsonProperty("_x")
    val x: Int,

    @param:JsonProperty("_y")
    @field:JsonProperty("_y")
    val y: Int
)
