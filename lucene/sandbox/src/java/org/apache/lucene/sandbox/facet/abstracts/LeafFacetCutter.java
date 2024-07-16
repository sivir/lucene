/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.sandbox.facet.abstracts;

import java.io.IOException;

/**
 * Interface to be implemented to cut documents into facets for an index segment (leaf).
 *
 * <p>When {@link #advanceExact(int)} returns true, {@link #nextOrd()} yields all facet ordinals for
 * the current document. It is illegal to call {@link #nextOrd()} if {@link #advanceExact(int)}
 * returns false.
 */
public interface LeafFacetCutter extends OrdinalIterator {
  /** advance to the next doc */
  boolean advanceExact(int doc) throws IOException;
}