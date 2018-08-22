/*
 * Copyright (c) 2014-2018, Bolotin Dmitry, Chudakov Dmitry, Shugay Mikhail
 * (here and after addressed as Inventors)
 * All Rights Reserved
 *
 * Permission to use, copy, modify and distribute any part of this program for
 * educational, research and non-profit purposes, by non-profit institutions
 * only, without fee, and without a written agreement is hereby granted,
 * provided that the above copyright notice, this paragraph and the following
 * three paragraphs appear in all copies.
 *
 * Those desiring to incorporate this work into commercial products or use for
 * commercial purposes should contact MiLaboratory LLC, which owns exclusive
 * rights for distribution of this program for commercial purposes, using the
 * following email address: licensing@milaboratory.com.
 *
 * IN NO EVENT SHALL THE INVENTORS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 * SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 * ARISING OUT OF THE USE OF THIS SOFTWARE, EVEN IF THE INVENTORS HAS BEEN
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * THE SOFTWARE PROVIDED HEREIN IS ON AN "AS IS" BASIS, AND THE INVENTORS HAS
 * NO OBLIGATION TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 * MODIFICATIONS. THE INVENTORS MAKES NO REPRESENTATIONS AND EXTENDS NO
 * WARRANTIES OF ANY KIND, EITHER IMPLIED OR EXPRESS, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY OR FITNESS FOR A
 * PARTICULAR PURPOSE, OR THAT THE USE OF THE SOFTWARE WILL NOT INFRINGE ANY
 * PATENT, TRADEMARK OR OTHER RIGHTS.
 */
package com.milaboratory.mixcr.cli;

import cc.redberry.pipe.CUtils;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.milaboratory.cli.Action;
import com.milaboratory.cli.ActionHelper;
import com.milaboratory.cli.ActionParameters;
import com.milaboratory.core.io.sequence.PairedRead;
import com.milaboratory.core.io.sequence.SequenceRead;
import com.milaboratory.core.io.sequence.SequenceWriter;
import com.milaboratory.core.io.sequence.SingleReadImpl;
import com.milaboratory.core.io.sequence.fasta.FastaSequenceWriterWrapper;
import com.milaboratory.core.io.sequence.fastq.PairedFastqWriter;
import com.milaboratory.core.io.sequence.fastq.SingleFastqWriter;
import com.milaboratory.core.sequence.NSequenceWithQuality;
import com.milaboratory.mixcr.assembler.AlignmentsToClonesMappingContainer;
import com.milaboratory.mixcr.assembler.ReadToCloneMapping;
import com.milaboratory.mixcr.basictypes.VDJCAlignments;
import com.milaboratory.mixcr.basictypes.VDJCAlignmentsReader;
import gnu.trove.map.hash.TIntObjectHashMap;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * @author Dmitry Bolotin
 * @author Stanislav Poslavsky
 */
public final class ActionExportCloneReads implements Action {
    private final ExtractCloneParameters parameters = new ExtractCloneParameters();

    @Override
    public String command() {
        return "exportReadsForClones";
    }

    @Override
    public ActionParameters params() {
        return parameters;
    }

    @Override
    public void go(ActionHelper helper) throws Exception {
        if (!originalReadsPresent()) {
            final String msg = "Error: original reads was not saved in the .vdjca file: re-run align with '-g' option.";
            throw new IllegalArgumentException(msg);
        }

        try (AlignmentsToClonesMappingContainer index = AlignmentsToClonesMappingContainer.open(parameters.getIndexFile())) {
            int[] cloneIds = parameters.getCloneIds();
            if (cloneIds.length == 1) //byClones
                writeSingle(index, cloneIds[0]);
            else
                writeMany(index, cloneIds);
        }
    }

    private boolean originalReadsPresent() throws IOException {
        try (VDJCAlignmentsReader reader = new VDJCAlignmentsReader(parameters.getAlignmentsFile())) {
            VDJCAlignments test = reader.take();
            return test == null || test.getOriginalSequences() != null;
        }
    }

    public void writeMany(AlignmentsToClonesMappingContainer index, int[] cloneIds)
            throws Exception {
        TIntObjectHashMap<SequenceWriter> writers = new TIntObjectHashMap<>(cloneIds.length);
        for (int cloneId : cloneIds)
            writers.put(cloneId, null);

        try (VDJCAlignmentsReader reader = new VDJCAlignmentsReader(parameters.getAlignmentsFile())) {
            Iterator<ReadToCloneMapping> mappingIterator = CUtils.it(index.createPortByClones()).iterator();
            Iterator<VDJCAlignments> vdjcaIterator = new CUtils.OPIterator<>(reader);

            for (; mappingIterator.hasNext() && vdjcaIterator.hasNext(); ) {
                //mapping = mappingIterator.next();
                ReadToCloneMapping mapping = mappingIterator.next();
                if (!writers.containsKey(mapping.getCloneIndex()))
                    continue;
                VDJCAlignments vdjca = vdjcaIterator.next();
                while (vdjca.getAlignmentsIndex() < mapping.getAlignmentsId()
                        && vdjcaIterator.hasNext())
                    vdjca = vdjcaIterator.next();

                assert vdjca.getAlignmentsIndex() == mapping.getAlignmentsId();

                SequenceWriter writer = writers.get(mapping.getCloneIndex());
                if (writer == null)
                    writers.put(mapping.getCloneIndex(), writer = createWriter(vdjca.getOriginalSequences().length == 2,
                            createFileName(parameters.getOutputFileName(), mapping.getCloneIndex())));
                writer.write(createRead(vdjca.getOriginalSequences(), vdjca.getOriginalDescriptions()));
            }

            for (SequenceWriter writer : writers.valueCollection())
                if (writer != null)
                    writer.close();
        }
    }


    public void writeSingle(AlignmentsToClonesMappingContainer index, int cloneId)
            throws Exception {
        try (VDJCAlignmentsReader reader = new VDJCAlignmentsReader(parameters.getAlignmentsFile())) {
            Iterator<ReadToCloneMapping> mappingIterator = CUtils.it(index.createPortForClone(cloneId)).iterator();
            Iterator<VDJCAlignments> vdjcaIterator = new CUtils.OPIterator<>(reader);

            SequenceWriter writer = null;
            for (; mappingIterator.hasNext() && vdjcaIterator.hasNext(); ) {
                //mapping = mappingIterator.next();
                VDJCAlignments vdjca = vdjcaIterator.next();
                ReadToCloneMapping mapping = mappingIterator.next();
                while (vdjca.getAlignmentsIndex() < mapping.getAlignmentsId()
                        && vdjcaIterator.hasNext())
                    vdjca = vdjcaIterator.next();

                if (vdjca.getAlignmentsIndex() != mapping.getAlignmentsId())
                    continue;

                if (writer == null)
                    writer = createWriter(vdjca.getOriginalSequences().length == 2,
                            createFileName(parameters.getOutputFileName(), cloneId));
                writer.write(createRead(vdjca.getOriginalSequences(), vdjca.getOriginalDescriptions()));
            }
            if (writer != null)
                writer.close();
        }
    }

    private static String createFileName(String fileName, int id) {
        if (fileName.contains(".fast"))
            fileName = fileName.replace(".fast", "_cln" + id + ".fast");
        else fileName += id;
        return fileName;
    }

    private static SequenceRead createRead(NSequenceWithQuality[] nseqs, String[] descr) {
        if (nseqs.length == 1)
            return new SingleReadImpl(-1, nseqs[0], descr[0]);
        else {
            String descr1, descr2;
            if (descr == null)
                descr1 = descr2 = "";
            else if (descr.length == 1)
                descr1 = descr2 = descr[0];
            else {
                descr1 = descr[0];
                descr2 = descr[1];
            }

            return new PairedRead(
                    new SingleReadImpl(-1, nseqs[0], descr1),
                    new SingleReadImpl(-1, nseqs[1], descr2));
        }
    }

    private static SequenceWriter createWriter(boolean paired, String fileName)
            throws Exception {
        String[] split = fileName.split("\\.");
        String ext = split[split.length - 1];
        boolean gz = ext.equals("gz");
        if (gz)
            ext = split[split.length - 2];
        if (ext.equals("fasta")) {
            if (paired)
                throw new IllegalArgumentException("Fasta does not support paired reads.");
            return new FastaSequenceWriterWrapper(fileName);
        } else if (ext.equals("fastq")) {
            if (paired) {
                String fileName1 = fileName.replace(".fastq", "_R1.fastq");
                String fileName2 = fileName.replace(".fastq", "_R2.fastq");
                return new PairedFastqWriter(fileName1, fileName2);
            } else return new SingleFastqWriter(fileName);
        }

        if (paired)
            return new PairedFastqWriter(fileName + "_R1.fastq.gz", fileName + "_R2.fastq.gz");
        else return new SingleFastqWriter(fileName + ".fastq.gz");
    }

    @Parameters(commandDescription = "Export reads for particular clones.")
    public static final class ExtractCloneParameters extends ActionParameters {
        @Parameter(description = "mappingFile vdjcaFile clone1 [clone2] [clone3] ... output")
        public List<String> parameters;

        public String getIndexFile() {
            return parameters.get(0);
        }

        public String getAlignmentsFile() {
            return parameters.get(1);
        }

        public int[] getCloneIds() {
            int[] cloneIds = new int[parameters.size() - 3];
            for (int i = 2; i < parameters.size() - 1; ++i)
                cloneIds[i - 2] = Integer.valueOf(parameters.get(i));
            return cloneIds;
        }

        public String getOutputFileName() {
            return parameters.get(parameters.size() - 1);
        }

        @Override
        public void validate() {
            if (parameters.size() < 4)
                throw new ParameterException("Required parameters missing.");
            super.validate();
        }
    }
}
