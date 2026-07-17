package com.example.flightlog.tracking

internal class MotionTelemetryBuffer {
    private val accelerometer = ArrayList<Vector3Sample>(1_024)
    private val gyroscope = ArrayList<Vector3Sample>(1_024)
    private val orientation = ArrayList<RotationSample>(1_024)
    private val pressure = ArrayList<PressureSample>(256)

    @Synchronized fun addAcceleration(sample: Vector3Sample): Int {
        accelerometer += sample
        return size()
    }

    @Synchronized fun addGyroscope(sample: Vector3Sample): Int {
        gyroscope += sample
        return size()
    }

    @Synchronized fun addOrientation(sample: RotationSample): Int {
        orientation += sample
        return size()
    }

    @Synchronized fun addPressure(sample: PressureSample): Int {
        pressure += sample
        return size()
    }

    @Synchronized fun drain(source: OrientationSource): MotionTelemetry {
        if (size() == 0) return MotionTelemetry.EMPTY
        return MotionTelemetry(
            orientationSource = source,
            accelerometer = accelerometer.toList(),
            gyroscope = gyroscope.toList(),
            orientation = orientation.toList(),
            pressure = pressure.toList(),
        ).also {
            accelerometer.clear()
            gyroscope.clear()
            orientation.clear()
            pressure.clear()
        }
    }

    private fun size(): Int = accelerometer.size + gyroscope.size + orientation.size + pressure.size
}
