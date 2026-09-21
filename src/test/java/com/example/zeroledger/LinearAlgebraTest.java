package com.example.zeroledger;

import static com.example.zeroledger.domain.LinearAlgebra.cross;
import static com.example.zeroledger.domain.LinearAlgebra.crossMatrix;
import static com.example.zeroledger.domain.LinearAlgebra.multiply;
import static com.example.zeroledger.domain.LinearAlgebra.rotationY;
import static com.example.zeroledger.domain.LinearAlgebra.rotationZ;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LinearAlgebraTest {
    @Test
    void crossProductUsesRightHandedConvention() {
        assertThat(cross(new double[]{1, 0, 0}, new double[]{0, 1, 0})).containsExactly(0, 0, 1);
        assertThat(multiply(crossMatrix(new double[]{0.1, 0.02, 0}), new double[]{100, 60, -100}))
                .containsExactly(-2, 10, 4);
    }

    @Test
    void swappedRotationOrderProducesDifferentMatrixForAlphaAndBeta() {
        double alpha = 0.12;
        double beta = 0.08;
        double[][] alphaThenBeta = multiply(rotationZ(beta), rotationY(alpha));
        double[][] betaThenAlpha = multiply(rotationY(alpha), rotationZ(beta));
        assertThat(alphaThenBeta).isNotEqualTo(betaThenAlpha);
        assertThat(alphaThenBeta[0][2]).isNotEqualTo(betaThenAlpha[0][2]);
    }
}
