/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.util.xmlb

import com.intellij.util.loadElement
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.Tag
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.*

class KotlinXmlSerializerTest {
  @Test fun internalVar() {
    @Tag("bean")
    class Foo {
      internal var PLACES_MAP = ""
    }

    val data = Foo()
    data.PLACES_MAP = "new"
    doSerializerTest("""
    <bean>
      <option name="PLACES_MAP" value="new" />
    </bean>""", data)
  }

  @Test fun nullInMap() {
    @Tag("bean")
    class Foo {
      @MapAnnotation(surroundWithTag = false, surroundKeyWithTag = false, surroundValueWithTag = false)
      var PLACES_MAP: TreeMap<String, PlaceSettings> = TreeMap()
    }

    val data = Foo()
    data.PLACES_MAP.put("", PlaceSettings())
    doSerializerTest("""
    <bean>
      <option name="PLACES_MAP">
        <entry key="">
          <PlaceSettings>
            <option name="IGNORE_POLICY" value="DEFAULT" />
          </PlaceSettings>
        </entry>
      </option>
    </bean>""", data)

    assertThat(XmlSerializer.deserialize(loadElement("""<bean>
      <option name="PLACES_MAP">
        <entry key="">
          <PlaceSettings>
            <option name="IGNORE_POLICY" />
          </PlaceSettings>
        </entry>
      </option>
    </bean>"""), Foo::class.java)!!.PLACES_MAP.get("")!!.IGNORE_POLICY).isEqualTo(IgnorePolicy.DEFAULT)

    assertThat(XmlSerializer.deserialize(loadElement("""<bean>
      <option name="PLACES_MAP">
        <entry key="">
          <PlaceSettings>
            <option name="SOME_UNKNOWN_VALUE" />
          </PlaceSettings>
        </entry>
      </option>
    </bean>"""), Foo::class.java)!!.PLACES_MAP.get("")!!.IGNORE_POLICY).isEqualTo(IgnorePolicy.DEFAULT)
  }
}

private enum class IgnorePolicy(val text: String) {
  DEFAULT("Do not ignore"),
  TRIM_WHITESPACES("Trim whitespaces"),
  IGNORE_WHITESPACES("Ignore whitespaces"),
  IGNORE_WHITESPACES_CHUNKS("Ignore whitespaces and empty lines"),
  FORMATTING("Ignore formatting");
}

private data class PlaceSettings(var IGNORE_POLICY: IgnorePolicy = IgnorePolicy.DEFAULT)
