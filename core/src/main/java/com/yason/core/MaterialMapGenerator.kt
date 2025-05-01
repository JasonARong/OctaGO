package com.yason.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.pow
import kotlin.math.sqrt

class MaterialMapGenerator (
    private val images: List<Mat>,
    private val lightDirectionsUnnormalized: List<FloatArray>,
    private val imageNumber: Int = 8
){
    init {
        require(images.size == imageNumber) {
            "Expected $imageNumber images, but got ${images.size}"
        }
    }

    data class MaterialMaps(
        val normalMap: Mat,
        val albedoMap: Mat,
        val heightMap: Mat
    )

    fun generate(): MaterialMaps {
        val (normalMap, albedoMap) = generateNormalAndAlbedoMap()
        val heightMap = generateHeightMap(normalMap)
        return MaterialMaps(normalMap, albedoMap, heightMap)
    }

    /* ============================ Normal And Albedo Map Generation ============================ */
    private fun generateNormalAndAlbedoMap (): Pair<Mat, Mat>{
        // Normalize Light direction vectors into unit length
        val lightDirections = lightDirectionsUnnormalized.map {
            // magnitude = sqrt(x^2 + y^2 + z^2)
            val magnitude = sqrt((it[0].pow(2) + it[1].pow(2) + it[2].pow(2)).toDouble()).toFloat()
            // unit vector = (x/mag, y/mag, z/mag)
            if (magnitude > 1e-6f) {
                floatArrayOf(it[0]/magnitude, it[1]/magnitude, it[2]/magnitude)
            } else {
                floatArrayOf(0f, 0f, 0f) // Handle zero vector case
            }
        }.toTypedArray() //convert List<T> to Array<T>.

        // output setups
        val height = images[0].rows()
        val width = images[0].cols()
        val normalMap = Mat(height, width, CvType.CV_32FC3) // 3-channel 32-bit float
        val albedoMap = Mat(height, width, CvType.CV_32FC3)
        val gamma = 2.2

        // For computing the normal map
        val grayImages = images.map { img ->
            val gray = Mat()
            Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)  // Convert to grayscale
//            val corrected = applyGammaCorrection(gray, gamma) // gamma correction
            gray
        }

        // From original images to list of B,G,R channels
        val channelsPerImage = images.map { img ->
            val corrected = applyGammaCorrection(img, gamma) // gamma correction
            val mean = Core.mean(corrected)
            Log.d("AlbedoDebug", "Avg BGR: ${mean.`val`.joinToString()}")
            val channels = ArrayList<Mat>()
            Core.split(corrected, channels)
            channels // (B,G,R) channels for each img
        }


        // Parallel processing
        val numCores = Runtime.getRuntime().availableProcessors() // 4 cores in my case
        Log.d("Parallel Processing", "number of cores: $numCores")
        val rowsPerChunk = height / numCores // how many rows to process in 1 chunk of parallel job
        runBlocking {
            val jobs = mutableListOf<Job>()
            for (i in 0 until numCores) { // 4 cores in my case
                val startY = i * rowsPerChunk
                val endY = if (i == numCores - 1){ height } // last chunk
                else{ (i + 1) * rowsPerChunk }

                // launch jobs and append to jobs List
                jobs += launch(Dispatchers.Default) {
                    // Allocate shared memory buffers for reuse
                    // For Calculating Normal
                    val intensities = FloatArray(imageNumber)
                    val normalPixel = FloatArray(3)
                    val lightMatrix = Mat(imageNumber, 3, CvType.CV_32FC1)
                    val intensityVector = Mat(imageNumber, 1, CvType.CV_32FC1)
                    val normalVector = Mat()

                    // Each channel's intensity for Calculating albedo
                    val bIntensities = FloatArray(imageNumber)
                    val gIntensities = FloatArray(imageNumber)
                    val rIntensities = FloatArray(imageNumber)


                    for (y in startY until endY){
                        for (x in 0 until width){
                            for (j in 0 until imageNumber) {
                                // Grayscale intensities for normal calculation
                                intensities[j] = grayImages[j].get(y,x)[0].toFloat()

                                // Color channel intensities for albedo calculation
                                bIntensities[j] = channelsPerImage[j][0].get(y, x)[0].toFloat()  // B channel
                                gIntensities[j] = channelsPerImage[j][1].get(y, x)[0].toFloat()  // G channel
                                rIntensities[j] = channelsPerImage[j][2].get(y, x)[0].toFloat()  // R channel
                            }

                            // Solve for the normal at (x,y) with 8 intensities and 8 respective light srcs
                            val normal = solveNormal(intensities, lightDirections, lightMatrix, intensityVector, normalVector)
                            normalPixel[0] = normal[0]
                            normalPixel[1] = normal[1]
                            normalPixel[2] = normal[2]
                            normalMap.put(y, x, normalPixel)


                            // Calculate albedo for each channel with same normal
                            val sharedIndices = getSharedValidSampleIndices(lightDirections, normal)
                            val bAlbedo = calculateAlbedo(sharedIndices, bIntensities)
                            val gAlbedo = calculateAlbedo(sharedIndices, gIntensities)
                            val rAlbedo = calculateAlbedo(sharedIndices, rIntensities)
                            albedoMap.put(y,x, floatArrayOf(bAlbedo, gAlbedo, rAlbedo))
                        }
                        Log.d("Parallel Processing", "Finished row $y")
                    }
                    lightMatrix.release()
                    intensityVector.release()
                    normalVector.release()
                }
            }
            jobs.joinAll() // Wait for all coroutines to finish
        }

        // normalizes the normal map values to the 0-255 range suitable for image storage and display.
        val normalizedNormalMap = Mat()
        Core.normalize(normalMap, normalizedNormalMap, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8UC3)

        val normalizedAlbedoMap = Mat()
        Core.normalize(albedoMap, normalizedAlbedoMap, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8UC3)
        Core.multiply(normalizedAlbedoMap, Scalar(1.5, 1.5, 1.5), normalizedAlbedoMap)

        // Clean up temporary matrices
        normalMap.release()
        albedoMap.release()
        for(gray in grayImages){
            gray.release()
        }
        for(channels in channelsPerImage){
            for (channel in channels){
                channel.release()
            }
        }

        return Pair(normalizedNormalMap, normalizedAlbedoMap)
    }


    /* ============== Normal Map Calculation ============== */
    private fun solveNormal(
        intensities: FloatArray,
        lightDirections: Array<FloatArray>,
        lightMatrix: Mat,
        intensityVector: Mat,
        normalVector: Mat
    ): FloatArray {
        for (i in 0 until imageNumber) {
            lightMatrix.put(i, 0, lightDirections[i][0].toDouble())
            lightMatrix.put(i, 1, lightDirections[i][1].toDouble())
            lightMatrix.put(i, 2, lightDirections[i][2].toDouble())
            intensityVector.put(i, 0, intensities[i].toDouble())
        }

        // solve for normal given lightMatrix, and intensityVector
        Core.solve(lightMatrix, intensityVector, normalVector, Core.DECOMP_NORMAL)


        // normalVector mat to normal float array
        val normal = FloatArray(3)
        if (!normalVector.empty() && normalVector.rows() == 3 && normalVector.cols() == 1) {
            normal[0] = normalVector.get(0, 0)[0].toFloat()
            normal[1] = normalVector.get(1, 0)[0].toFloat()
            normal[2] = normalVector.get(2, 0)[0].toFloat()
        } else {
            Log.e("SolveNormal", "Normal Vector has incorrect dimensions or is empty after solve.")
            return floatArrayOf(0f, 0f, 0f)
        }

        val magnitude = sqrt((normal[0]*normal[0] + normal[1]*normal[1] + normal[2]*normal[2]).toDouble()).toFloat()

        // Normalize the vector into unit vector
        if (magnitude > 1e-6f) {
            normal[0] /= magnitude
            normal[1] /= magnitude
            normal[2] /= magnitude
        } else {
            Log.w("SolveNormal", "Normal vector magnitude is very small, returning zero normal.")
            return floatArrayOf(0f, 0f, 0f)
        }

        return normal
    }


    /* ============== Albedo Map Calculation ============== */
    private fun getSharedValidSampleIndices(
        lightDirections: Array<FloatArray>,
        normal: FloatArray
    ):List<Int>{
        return (0 until imageNumber).filter {i ->
            val dot = normal[0] * lightDirections[i][0] +
                    normal[1] * lightDirections[i][1] +
                    normal[2] * lightDirections[i][2]
            dot > 0.001f
        }
    }

    private fun calculateAlbedo(indices: List<Int>, data: FloatArray): Float {
        if (indices.isEmpty()) return 0f
        val temp = FloatArray(indices.size) { i -> data[indices[i]] }
        temp.sort()
        val mid = temp.size / 2
        return if (temp.size % 2 == 1) temp[mid]
        else (temp[mid - 1] + temp[mid]) / 2f
    }


    /* ============================ Height Map Generation ============================ */
    private fun generateHeightMap(normalMap: Mat): Mat {
        // Convert normal map to floating point format
        val normalMapFloat = Mat()
        normalMap.convertTo(normalMapFloat, CvType.CV_32FC3, 1.0/255.0)

        // Split normal map into components
        val channels = ArrayList<Mat>()
        Core.split(normalMapFloat, channels)
        val nx = channels[0]
        val ny = channels[1]
        val nz = channels[2]

        // Calculate gradient fields (dz/dx and dz/dy)
        val dzdx = Mat(normalMap.rows(), normalMap.cols(), CvType.CV_32FC1)
        val dzdy = Mat(normalMap.rows(), normalMap.cols(), CvType.CV_32FC1)


        // Create a safeguard for nz to avoid division by zero
        val nzSafe = Mat()
        Core.max(nz, Scalar(0.001), nzSafe)

        // Calculate -nx/nz and -ny/nz using vectorized operations
        Core.divide(nx, nzSafe, dzdx)
        Core.multiply(dzdx, Scalar(-1.0), dzdx)
        Core.divide(ny, nzSafe, dzdy)
        Core.multiply(dzdy, Scalar(-1.0), dzdy)

        val meanX = Core.mean(dzdx).`val`[0]
        val meanY = Core.mean(dzdy).`val`[0]
        Core.subtract(dzdx, Scalar(meanX), dzdx)
        Core.subtract(dzdy, Scalar(meanY), dzdy)

        // Solve Poisson equation to reconstruct height from gradients
        val heightMap = solvePoissonEquation(dzdx, dzdy)

        // Normalize height map
        Core.normalize(heightMap, heightMap, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8UC1)

        // Apply smoothing
        val smoothedHeight = Mat()
        Imgproc.bilateralFilter(heightMap, smoothedHeight, 9, 75.0, 75.0)

        var finalHeightMap = balancedHeightEnhance(smoothedHeight)

        // Clean up temporary matrices
        normalMapFloat.release()
        nx.release()
        ny.release()
        nz.release()
        nzSafe.release()
        dzdx.release()
        dzdy.release()
        smoothedHeight.release()
        heightMap.release()

        return finalHeightMap
    }
    private fun solvePoissonEquation(dzdx: Mat, dzdy: Mat): Mat {
        val size = dzdx.size()
        val w = size.width.toInt()
        val h = size.height.toInt()

        Log.d("Height map", "Merge gradients into complex Mats")
        // Merge gradients into complex Mats
        val zeros = Mat.zeros(size, CvType.CV_32F)
        val complexGx = Mat()
        val complexGy = Mat()
        Core.merge(listOf(dzdx, zeros), complexGx)
        Core.merge(listOf(dzdy, zeros), complexGy)

        Log.d("Height map", "DFT")
        // DFT
        val GxF = Mat()
        val GyF = Mat()
        Core.dft(complexGx, GxF, Core.DFT_COMPLEX_OUTPUT)
        Core.dft(complexGy, GyF, Core.DFT_COMPLEX_OUTPUT)

        // Release matrices we no longer need
        complexGx.release()
        complexGy.release()

        val Hf = Mat(size, CvType.CV_32FC2)
        val pi2 = (2 * Math.PI).toFloat()


        Log.d("Height map", "Starting parallel pixel calculation")
        // Parallel processing setup
        val numCores = Runtime.getRuntime().availableProcessors()
        val rowsPerChunk = h / numCores

        runBlocking {
            val jobs = mutableListOf<Job>()
            for (i in 0 until numCores) {
                val startY = i * rowsPerChunk
                val endY = if (i == numCores - 1) h else (i + 1) * rowsPerChunk

                jobs += launch(Dispatchers.Default) {
                    // Reuse array for better performance
                    val tempArray = FloatArray(2)

                    for (v in startY until endY) {
                        for (u in 0 until w) {
                            val fx = if (u <= w / 2) u.toFloat() / w else (u - w).toFloat() / w
                            val fy = if (v <= h / 2) v.toFloat() / h else (v - h).toFloat() / h

                            if (u == 0 && v == 0) {
                                // Proper DC component handling
                                tempArray[0] = 0f
                                tempArray[1] = 0f
                                Hf.put(v, u, tempArray)
                                continue
                            }

                            val denom = 4 * pi2 * pi2 * (fx * fx + fy * fy)
                            if (denom == 0f) {
                                tempArray[0] = 0f
                                tempArray[1] = 0f
                                Hf.put(v, u, tempArray)
                                continue
                            }

                            val gx = GxF.get(v, u)
                            val gy = GyF.get(v, u)

                            // i*fx * Gx + i*fy * Gy
                            val realPart = (fx * gy!![1] - fy * gx!![1]) / denom
                            val imagPart = (-fx * gy[0] + fy * gx[0]) / denom

                            tempArray[0] = realPart.toFloat()
                            tempArray[1] = imagPart.toFloat()
                            Hf.put(v, u, tempArray)
                        }
                    }
                }
            }
            jobs.joinAll() // Wait for all coroutines to finish
        }
        Log.d("Height map", "Parallel processing completed")

        val heightMap = Mat()
        Core.idft(Hf, heightMap, Core.DFT_SCALE or Core.DFT_REAL_OUTPUT)

        // Clean up
        zeros.release()
        GxF.release()
        GyF.release()
        Hf.release()

        return heightMap
    }
    private fun balancedHeightEnhance(input: Mat): Mat {
        // Convert to float for accuracy
        val input32F = Mat()
        input.convertTo(input32F, CvType.CV_32F)

        // Blur the base shape (remove low-frequency lighting)
        val blurred = Mat()
        Imgproc.GaussianBlur(input32F, blurred, Size(51.0, 51.0), 0.0)

        // Extract detail (high-pass)
        val detail = Mat()
        Core.subtract(input32F, blurred, detail)

        // Slightly amplify the detail
        val detailWeight = Mat()
        Imgproc.Sobel(input32F, detailWeight, CvType.CV_32F, 1, 1)
        Core.normalize(detailWeight, detailWeight, 0.0, 2.0, Core.NORM_MINMAX)
        Core.multiply(detail, detailWeight, detail)

        // Add detail back to base to restore surface
        val combined = Mat()
        Core.add(blurred, detail, combined)

        // Normalize final result to 0–255 range
        val normalized = Mat()
        Core.normalize(combined, normalized, 0.0, 255.0, Core.NORM_MINMAX)
        normalized.convertTo(normalized, CvType.CV_8UC1)

        input32F.release()
        blurred.release()
        detail.release()
        combined.release()
        detailWeight.release()

        return normalized
    }



    /* ============================ Gamma Correction ============================ */
    // Nonlinear brightness adjustment
    // OG image: gamma-encoded, pixel values are not proportional to actual light intensity
    // Albedo calculations require linear relationship between light and pixel intensity.
    // intensity = albedo × (normal · light direction)
    private fun applyGammaCorrection(input: Mat, gamma: Double): Mat {
        val corrected = Mat()
        input.convertTo(corrected, CvType.CV_32F)          // 32-bit floating-point[0.0, 255.0], for calculation precision

        val scalar = Scalar(255.0, 255.0, 255.0)

        Core.divide(corrected, scalar, corrected)   // Divides by 255.0 to scale values to [0.0, 1.0]
        Core.pow(corrected, gamma, corrected)       // apply gamma correction, corrected = corrected ^ gamma
        Core.multiply(corrected, scalar, corrected) // Multiplies by 255.0 to return to the original range

        corrected.convertTo(corrected, CvType.CV_8U)       // 8-bit unsigned integer [0-255]
        return corrected
    }
}