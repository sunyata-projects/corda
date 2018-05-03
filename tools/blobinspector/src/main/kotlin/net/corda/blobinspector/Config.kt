package net.corda.blobinspector

data class Config(val verbose: Boolean, val schema: Boolean, val transforms: Boolean, val data: Boolean)