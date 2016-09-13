/* 
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

// TODO infer section from the event source perhaps?
function showHidePipelineSection(id) {
    var link = document.getElementById('show-hide-' + id)
    var display
    if (link.textContent === 'hide') {
        display = 'none'
        link.textContent = 'show'
    } else {
        display = 'inline'
        link.textContent = 'hide'
    }
    var sect = '.pipeline-sect-' + id
    var ss = document.styleSheets[0]
    for (var i = 0; i < ss.rules.length; i++) {
        if (ss.rules[i].selectorText === sect) {
            ss.rules[i].style.display = display
            return
        }
    }
    // TODO order rules, so that hiding and reshowing a high-level section will restore expansion of a lower-level section
    ss.insertRule(sect + ' {display: ' + display + '}', ss.rules.length)
}
