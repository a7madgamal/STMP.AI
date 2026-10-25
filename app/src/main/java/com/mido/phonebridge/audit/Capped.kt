package com.mido.phonebridge.audit

/** A string that may have been cut to the byte cap, and how long it really was. */
class Capped(val text: String, val originalBytes: Int)
