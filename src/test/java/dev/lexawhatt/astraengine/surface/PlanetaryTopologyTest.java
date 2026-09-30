package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanetaryTopologyTest {
    private static final double EARTH_RADIUS = 6_371_000;

    @Test
    void allTileCentersHaveOneRoundTripAddressWithoutAllocatingTheGlobe() {
        for (int level = 0; level <= 4; level++) {
            PlanetaryTopology topology = topology(level);
            Set<PlanetaryTile> owners = new HashSet<>();
            for (CubeFace face : CubeFace.values()) {
                for (int column = 0; column < topology.cellsPerFace(); column++) {
                    for (int row = 0; row < topology.cellsPerFace(); row++) {
                        PlanetaryTile tile = new PlanetaryTile(1, level, face, column, row);
                        assertEquals(tile, topology.locate(topology.direction(tile, .5, .5)));
                        assertEquals(tile, PlanetaryTile.parse(tile.key()));
                        assertTrue(owners.add(tile));
                    }
                }
            }
            assertEquals(topology.tileCount(), owners.size());
        }
        PlanetaryTopology deepest = topology(PlanetaryTopology.MAX_LEVEL);
        assertEquals(1_688_849_860_263_936L, deepest.tileCount());
        assertTrue(deepest.tileCount() > Integer.MAX_VALUE);
        assertEquals("v1/12/px/2048/2048", topology(12).locate(new SpaceVector(1, 0, 0)).key());
    }

    @Test
    void exactFaceEdgesCornersAndInternalLinesHaveDeterministicCanonicalOwners() {
        PlanetaryTopology topology = topology(3);
        for (int x : new int[]{-1, 1}) {
            for (int y : new int[]{-1, 1}) {
                for (int z : new int[]{-1, 1}) {
                    SpaceVector corner = new SpaceVector(x, y, z);
                    CubeFace expected = x > 0 ? CubeFace.POSITIVE_X : CubeFace.NEGATIVE_X;
                    assertEquals(expected, topology.locate(corner).face());
                    assertEquals(topology.locate(corner), topology.locate(corner.multiply(Double.MAX_VALUE)));
                    assertEquals(topology.locate(corner), topology.locate(corner.multiply(Double.MIN_VALUE)));
                }
            }
        }
        assertEquals(CubeFace.POSITIVE_Y, topology.locate(new SpaceVector(0, 1, 1)).face());
        assertEquals(CubeFace.NEGATIVE_Y, topology.locate(new SpaceVector(0, -1, 1)).face());
        PlanetaryTile center = topology.locate(new SpaceVector(1, 0, 0));
        assertEquals(4, center.column());
        assertEquals(4, center.row());
        assertEquals(3, topology.locate(new SpaceVector(1, 0, 1e-10)).column());
        assertEquals(4, topology.locate(new SpaceVector(1, 0, -1e-10)).column());
        assertEquals(7, topology.locate(new SpaceVector(1, -1, -1)).column());
        assertEquals(7, topology.locate(new SpaceVector(1, -1, -1)).row());
        assertNotEquals(topology.locate(new SpaceVector(1 + 1e-12, 1, 0)).face(),
                topology.locate(new SpaceVector(1, 1 + 1e-12, 0)).face());
    }

    @Test
    void exactDyadicGridLinesAndTheirImmediateFloatingNeighborsKeepDistinctOwners() {
        for (int level : new int[]{1, 4, 12, 24}) {
            PlanetaryTopology topology = topology(level);
            int count = topology.cellsPerFace();
            for (CubeFace face : CubeFace.values()) {
                for (int line : new int[]{1, count / 2, count - 1}) {
                    double coordinate = 2.0 * line / count - 1;
                    for (double scale : new double[]{1, Math.scalb(1.0, -900), Math.scalb(1.0, 900)}) {
                        double center = coordinate * scale;
                        SpaceVector exact = face.outward().multiply(scale).add(face.u().multiply(center));
                        SpaceVector below = face.outward().multiply(scale)
                                .add(face.u().multiply(Math.nextDown(center)));
                        SpaceVector above = face.outward().multiply(scale)
                                .add(face.u().multiply(Math.nextUp(center)));
                        assertEquals(line, topology.locate(exact).column());
                        assertEquals(line - 1, topology.locate(below).column());
                        assertEquals(line, topology.locate(above).column());
                    }
                }
            }
        }
    }

    @Test
    void sharedGeneratedFaceEdgeSamplesHaveOneOwnerAtEverySupportedScale() {
        for (int level : new int[]{0, 1, 4, 12, 24}) {
            PlanetaryTopology topology = topology(level);
            int last = topology.cellsPerFace() - 1;
            for (CubeFace face : CubeFace.values()) {
                for (TileEdge edge : TileEdge.values()) {
                    for (int along : new int[]{0, last / 3, last}) {
                        PlanetaryTile tile = new PlanetaryTile(1, level, face,
                                edge == TileEdge.LEFT ? 0 : edge == TileEdge.RIGHT ? last : along,
                                edge == TileEdge.TOP ? 0 : edge == TileEdge.BOTTOM ? last : along);
                        TileNeighbor neighbor = topology.neighbor(tile, edge);
                        for (double fraction : new double[]{0, .125, .5, .875, 1}) {
                            SpaceVector source = onEdge(topology, tile, edge, fraction);
                            SpaceVector target = onEdge(topology, neighbor.tile(), neighbor.entryEdge(),
                                    neighbor.crossingFraction(fraction));
                            assertVector(source, target, 0);
                            assertEquals(topology.locate(source), topology.locate(target));
                        }
                    }
                }
            }
        }
    }

    @Test
    void everyEdgeIsReciprocalAndBothTilesSampleTheSameClosedSeam() {
        PlanetaryTerrain terrain = new PlanetaryTerrain(1, PlanetaryTerrain.SEED);
        for (int level = 0; level <= 3; level++) {
            PlanetaryTopology topology = topology(level);
            for (CubeFace face : CubeFace.values()) {
                for (int column = 0; column < topology.cellsPerFace(); column++) {
                    for (int row = 0; row < topology.cellsPerFace(); row++) {
                        PlanetaryTile tile = new PlanetaryTile(1, level, face, column, row);
                        Set<PlanetaryTile> neighbors = new HashSet<>();
                        for (TileEdge edge : TileEdge.values()) {
                            TileNeighbor next = topology.neighbor(tile, edge);
                            assertTrue(neighbors.add(next.tile()));
                            TileNeighbor back = topology.neighbor(next.tile(), next.entryEdge());
                            assertEquals(tile, back.tile());
                            assertEquals(edge, back.entryEdge());
                            assertEquals(next.reversed(), back.reversed());
                            for (double fraction : new double[]{0, .125, .37, .5, .875, .9, 1}) {
                                double mapped = next.crossingFraction(fraction);
                                assertEquals(fraction, back.crossingFraction(mapped), 1e-15);
                                SpaceVector a = onEdge(topology, tile, edge, fraction);
                                SpaceVector b = onEdge(topology, next.tile(), next.entryEdge(), mapped);
                                assertVector(a, b, 1e-15);
                                assertEquals(terrain.sample(a).heightMeters(), terrain.sample(b).heightMeters(), 1e-6);
                                assertEquals(topology.locate(a), topology.locate(b));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void polarFaceCrossingsRotateAndReverseEdgeCoordinates() {
        PlanetaryTopology topology = topology(3);
        PlanetaryTile source = new PlanetaryTile(1, 3, CubeFace.POSITIVE_X, 2, 0);
        TileNeighbor north = topology.neighbor(source, TileEdge.TOP);
        assertEquals(new PlanetaryTile(1, 3, CubeFace.POSITIVE_Y, 7, 5), north.tile());
        assertEquals(TileEdge.RIGHT, north.entryEdge());
        assertTrue(north.reversed());
        assertEquals(.75, north.crossingFraction(.25));
        TileNeighbor east = topology.neighbor(source, TileEdge.RIGHT);
        assertEquals(source.face(), east.tile().face());
        assertFalse(east.reversed());
        assertEquals(TileEdge.LEFT, east.entryEdge());
    }

    @Test
    void fullEquatorialAndPolarTileCircuitsCloseWithTheirOriginalOrientation() {
        for (int level : new int[]{0, 1, 4, 12}) {
            PlanetaryTopology topology = topology(level);
            int middle = topology.cellsPerFace() / 2;
            PlanetaryTile start = new PlanetaryTile(1, level, CubeFace.POSITIVE_X, middle, middle);
            assertCircuit(topology, start, TileEdge.RIGHT,
                    Set.of(CubeFace.POSITIVE_X, CubeFace.NEGATIVE_Z, CubeFace.NEGATIVE_X, CubeFace.POSITIVE_Z));
            assertCircuit(topology, start, TileEdge.TOP,
                    Set.of(CubeFace.POSITIVE_X, CubeFace.POSITIVE_Y, CubeFace.NEGATIVE_X, CubeFace.NEGATIVE_Y));
        }
    }

    @Test
    void continuousGreatCirclesResolveAcrossLongitudeWrapAndBothPoles() {
        PlanetaryTopology topology = topology(12);
        Set<CubeFace> equatorial = new HashSet<>(), polar = new HashSet<>();
        for (int step = 0; step < 1440; step++) {
            double angle = step * Math.PI / 720;
            PlanetaryTile equator = topology.locate(new SpaceVector(Math.cos(angle), 0, Math.sin(angle)));
            PlanetaryTile meridian = topology.locate(new SpaceVector(Math.cos(angle), Math.sin(angle), 0));
            equatorial.add(equator.face());
            polar.add(meridian.face());
        }
        assertEquals(Set.of(CubeFace.POSITIVE_X, CubeFace.NEGATIVE_X,
                CubeFace.POSITIVE_Z, CubeFace.NEGATIVE_Z), equatorial);
        assertEquals(Set.of(CubeFace.POSITIVE_X, CubeFace.NEGATIVE_X,
                CubeFace.POSITIVE_Y, CubeFace.NEGATIVE_Y), polar);
        assertEquals(CubeFace.POSITIVE_Y, topology.locate(new SpaceVector(0, 1, 0)).face());
        assertEquals(CubeFace.NEGATIVE_Y, topology.locate(new SpaceVector(0, -1, 0)).face());
    }

    @Test
    void tangentFramesAreRightHandedAndPreservePointsDirectionsAndCameraRollAtPolesAndSeams() {
        PlanetaryTopology topology = topology(2);
        FlightOrientation pose = FlightOrientation.fromAngles(31, -73, 147);
        SpaceVector localPoint = new SpaceVector(1432.12, -73.75, -904.25);
        for (CubeFace face : CubeFace.values()) {
            PlanetaryTile tile = new PlanetaryTile(1, 2, face, 0, 0);
            for (double u : new double[]{0, .3, 1}) {
                for (double v : new double[]{0, .6, 1}) {
                    PlanetaryFrame frame = topology.frame(tile, u, v);
                    assertEquals(EARTH_RADIUS, frame.originMeters().length(), 2e-9);
                    assertVector(frame.zAxis(), PlanetaryFrame.cross(frame.xAxis(), frame.upAxis()), 1e-15);
                    assertVector(localPoint, frame.toLocalPoint(frame.toBodyPoint(localPoint)), 3e-9);
                    assertVector(localPoint, frame.toLocalDirection(frame.toBodyDirection(localPoint)), 1e-10);
                    FlightOrientation restored = frame.toLocalOrientation(frame.toBodyOrientation(pose));
                    assertVector(pose.forward(), restored.forward(), 1e-14);
                    assertVector(pose.up(), restored.up(), 1e-14);
                    assertVector(frame.xAxis(), frame.orientation().left(), 1e-14);
                }
            }
            for (TileEdge edge : TileEdge.values()) {
                TileNeighbor neighbor = topology.neighbor(tile, edge);
                PlanetaryFrame first = frameOnEdge(topology, tile, edge, .25);
                PlanetaryFrame second = frameOnEdge(topology, neighbor.tile(), neighbor.entryEdge(),
                        neighbor.crossingFraction(.25));
                SpaceVector physical = first.toBodyDirection(localPoint);
                assertVector(physical, second.toBodyDirection(second.toLocalDirection(physical)), 1e-10);
                assertVector(first.originMeters(), second.originMeters(), 2e-9);
            }
            PlanetaryTile poleTile = topology.locate(face.outward());
            PlanetaryFrame center = topology.frame(poleTile, 0, 0);
            assertVector(face.outward(), center.upAxis(), 0);
        }
    }

    @Test
    void unknownVersionsAliasesInvalidFractionsAndForeignGridsFailWithoutFallback() {
        assertThrows(IllegalArgumentException.class, () -> new PlanetaryTopology(2, 12, EARTH_RADIUS));
        assertThrows(IllegalArgumentException.class, () -> topology(-1));
        assertThrows(IllegalArgumentException.class, () -> topology(25));
        assertThrows(IllegalArgumentException.class, () -> new PlanetaryTopology(1, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new PlanetaryTopology(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CubeFace.fromId(null));
        for (String key : new String[]{"", "v1/03/px/0/0", "v1/3/PX/0/0", "v01/3/px/0/0",
                "v1/3/px/-0/0", "v1/3/px/+0/0", "v1/3/px/8/0", "v1/3/px/0/0/", "v2/3/px/0/0",
                "v1/3/px/99999999999999/0", "v1/3/px/ 0/0"}) {
            assertThrows(IllegalArgumentException.class, () -> PlanetaryTile.parse(key), key);
        }
        assertThrows(IllegalArgumentException.class, () -> PlanetaryTile.parse(null));
        assertThrows(IllegalArgumentException.class, () -> topology(3).locate(null));
        assertThrows(IllegalArgumentException.class, () -> topology(3).locate(SpaceVector.ZERO));
        assertThrows(IllegalArgumentException.class, () -> topology(3).locate(new SpaceVector(-0.0, 0, 0)));
        PlanetaryTile tile = topology(3).locate(new SpaceVector(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> topology(2).direction(tile, .5, .5));
        assertThrows(IllegalArgumentException.class, () -> topology(3).direction(tile, -.01, .5));
        assertThrows(IllegalArgumentException.class, () -> topology(3).frame(tile, .5, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> topology(3).neighbor(tile, null));
        assertThrows(IllegalArgumentException.class, () -> topology(3).neighbor(tile, TileEdge.TOP)
                .crossingFraction(1.01));
        assertThrows(IllegalArgumentException.class, () -> new PlanetaryFrame(new SpaceVector(1, 0, 0),
                new SpaceVector(0, 0, -1), new SpaceVector(1, 0, 0), new SpaceVector(0, 1, 0)));
    }

    private static void assertCircuit(PlanetaryTopology topology, PlanetaryTile start, TileEdge initialDirection,
            Set<CubeFace> expectedFaces) {
        PlanetaryTile current = start;
        TileEdge direction = initialDirection;
        Set<PlanetaryTile> visited = new HashSet<>();
        Set<CubeFace> faces = new HashSet<>();
        for (int step = 0; step < 4 * topology.cellsPerFace(); step++) {
            assertTrue(visited.add(current), "A circuit must not repeat a tile before closing");
            faces.add(current.face());
            TileNeighbor next = topology.neighbor(current, direction);
            direction = next.entryEdge().opposite();
            current = next.tile();
        }
        assertEquals(start, current);
        assertEquals(initialDirection, direction);
        assertEquals(expectedFaces, faces);
    }

    private static SpaceVector onEdge(PlanetaryTopology topology, PlanetaryTile tile, TileEdge edge, double fraction) {
        return topology.direction(tile, edge == TileEdge.LEFT ? 0 : edge == TileEdge.RIGHT ? 1 : fraction,
                edge == TileEdge.TOP ? 0 : edge == TileEdge.BOTTOM ? 1 : fraction);
    }

    private static PlanetaryFrame frameOnEdge(PlanetaryTopology topology, PlanetaryTile tile,
            TileEdge edge, double fraction) {
        return topology.frame(tile, edge == TileEdge.LEFT ? 0 : edge == TileEdge.RIGHT ? 1 : fraction,
                edge == TileEdge.TOP ? 0 : edge == TileEdge.BOTTOM ? 1 : fraction);
    }

    private static PlanetaryTopology topology(int level) { return new PlanetaryTopology(1, level, EARTH_RADIUS); }

    private static void assertVector(SpaceVector expected, SpaceVector actual, double tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance);
        assertEquals(expected.y(), actual.y(), tolerance);
        assertEquals(expected.z(), actual.z(), tolerance);
    }
}
