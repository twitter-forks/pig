package org.apache.pig.backend.hadoop.executionengine.spark.converter;

import java.io.IOException;
import java.io.Serializable;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.POStatus;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.Result;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POPackage;
import org.apache.pig.backend.hadoop.executionengine.spark.SparkUtil;
import org.apache.pig.data.Tuple;
import org.apache.pig.impl.io.NullableTuple;
import org.apache.pig.impl.io.PigNullableWritable;

import scala.runtime.AbstractFunction1;
import spark.RDD;

@SuppressWarnings({ "serial"})
public class PackageConverter implements POConverter<Tuple, Tuple, POPackage> {
    private static final Log LOG = LogFactory.getLog(PackageConverter.class);

    @Override
    public RDD<Tuple> convert(List<RDD<Tuple>> predecessors, POPackage physicalOperator)
            throws IOException {
        SparkUtil.assertPredecessorSize(predecessors, physicalOperator, 1);
        RDD<Tuple> rdd = predecessors.get(0);
        // package will generate the group from the result of the local rearrange
        return rdd.map(new PackageFunction(physicalOperator), SparkUtil.getManifest(Tuple.class));
    }

    private static class PackageFunction extends AbstractFunction1<Tuple, Tuple> implements Serializable {

        private final POPackage physicalOperator;

        public PackageFunction(POPackage physicalOperator) {
            this.physicalOperator = physicalOperator;
        }

        @Override
        public Tuple apply(final Tuple t) {
            // (key, Seq<Tuple>:{(index, key, value without key)})
            if (LOG.isDebugEnabled())
                LOG.debug("PackageFunction in "+t);
            Result result;
            try {
                PigNullableWritable key = new PigNullableWritable() {

                    public Object getValueAsPigType() {
                        try {
                            Object keyTuple = t.get(0);
                            return keyTuple;
                        } catch (ExecException e) {
                           throw new RuntimeException(e);
                        }
                    }
                };
                final Iterator<Tuple> bagIterator = (Iterator<Tuple>)t.get(1);
                Iterator<NullableTuple> iterator = new Iterator<NullableTuple>() {
                    public boolean hasNext() {
                        return bagIterator.hasNext();
                    }
                    public NullableTuple next() {
                        try {
                            // we want the value and index only
                            Tuple next = bagIterator.next();
                            NullableTuple nullableTuple = new NullableTuple((Tuple)next.get(2));
                            nullableTuple.setIndex(((Number)next.get(0)).byteValue());
                            return nullableTuple;
                        } catch (ExecException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
                physicalOperator.setInputs(null);
                physicalOperator.attachInput(key, iterator);
                result = physicalOperator.getNextTuple();
            } catch (ExecException e) {
                throw new RuntimeException("Couldn't do Package on tuple: " + t, e);
            }

            if (result == null) {
                throw new RuntimeException("Null response found for Package on tuple: " + t);
            }
            Tuple out;
            switch (result.returnStatus) {
                case POStatus.STATUS_OK:
                    // (key, {(value)...})
                    if (LOG.isDebugEnabled())
                        LOG.debug("PackageFunction out "+result.result);
                    out = (Tuple)result.result;
                    break;
                case POStatus.STATUS_NULL:
                    out = null;
                    break;
                default:
                    throw new RuntimeException("Unexpected response code from operator "+physicalOperator+" : " + result + " " + result.returnStatus);
            }
            if (LOG.isDebugEnabled())
                LOG.debug("PackageFunction out " + out);
            return out;
        }

    }

}
