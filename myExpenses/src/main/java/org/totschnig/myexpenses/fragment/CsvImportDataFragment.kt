package org.totschnig.myexpenses.fragment

import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.SparseBooleanArray
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import org.apache.commons.csv.CSVRecord
import org.json.JSONException
import org.json.JSONObject
import org.totschnig.myexpenses.MyApplication
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.activity.CsvImportActivity
import org.totschnig.myexpenses.activity.ProtectedFragmentActivity
import org.totschnig.myexpenses.databinding.ImportCsvDataBinding
import org.totschnig.myexpenses.databinding.ImportCsvDataRowBinding
import org.totschnig.myexpenses.dialog.ConfirmationDialogFragment
import org.totschnig.myexpenses.preference.PrefHandler
import org.totschnig.myexpenses.preference.PrefKey
import org.totschnig.myexpenses.util.SparseBooleanArrayParcelable
import org.totschnig.myexpenses.util.Utils
import org.totschnig.myexpenses.util.crashreporting.CrashHandler
import timber.log.Timber
import java.util.*
import javax.inject.Inject

class CsvImportDataFragment : Fragment() {
    private var _binding: ImportCsvDataBinding? = null
    private val binding get() = _binding!!
    private lateinit var dataSet: ArrayList<CSVRecord>
    private lateinit var selectedRows: SparseBooleanArrayParcelable
    private lateinit var mFieldAdapter: ArrayAdapter<Int>
    private lateinit var cellParams: LinearLayout.LayoutParams
    private var firstLineIsHeader = false
    private var nrOfColumns: Int = 0
    private val fieldKeys = arrayOf(
            FIELD_KEY_SELECT, FIELD_KEY_AMOUNT, FIELD_KEY_EXPENSE, FIELD_KEY_INCOME,
            FIELD_KEY_DATE, FIELD_KEY_PAYEE, FIELD_KEY_COMMENT, FIELD_KEY_CATEGORY,
            FIELD_KEY_SUBCATEGORY, FIELD_KEY_METHOD, FIELD_KEY_STATUS, FIELD_KEY_NUMBER,
            FIELD_KEY_SPLIT
    )
    private val fields = arrayOf(
            R.string.csv_import_discard,
            R.string.amount,
            R.string.expense,
            R.string.income,
            R.string.date,
            R.string.payer_or_payee,
            R.string.comment,
            R.string.category,
            R.string.subcategory,
            R.string.method,
            R.string.status,
            R.string.reference_number,
            R.string.split_transaction
    )
    private lateinit var header2FieldMap: JSONObject
    private var windowWidth = 0
    private var cellMinWidth = 0
    private var checkboxColumnWidth = 0
    private var cellMargin = 0

    @Inject
    lateinit var prefHandler: PrefHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (requireActivity().application as MyApplication).appComponent.inject(this)
        setHasOptionsMenu(true)
        cellMinWidth = resources.getDimensionPixelSize(R.dimen.csv_import_cell_min_width)
        checkboxColumnWidth = resources.getDimensionPixelSize(R.dimen.csv_import_checkbox_column_width)
        cellMargin = resources.getDimensionPixelSize(R.dimen.csv_import_cell_margin)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val displayMetrics = resources.displayMetrics
        windowWidth = (displayMetrics.widthPixels / displayMetrics.density).toInt()
        header2FieldMap = prefHandler.getString(PrefKey.CSV_IMPORT_HEADER_TO_FIELD_MAP, null)?.let {
            try {
                JSONObject(it)
            } catch (e: JSONException) {
                null
            }
        } ?: JSONObject()
        mFieldAdapter = object : ArrayAdapter<Int>(
                requireActivity(), R.layout.spinner_item_narrow, 0, fields) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.text = getString(fields[position])
                return tv
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getDropDownView(position, convertView, parent) as TextView
                tv.text = getString(fields[position])
                return tv
            }
        }.also {
            it.setDropDownViewResource(R.layout.support_simple_spinner_dropdown_item)
        }
        _binding = ImportCsvDataBinding.inflate(inflater, container, false)

        // use this setting to improve performance if you know that changes
        // in content do not change the layout size of the RecyclerView
        // http://www.vogella.com/tutorials/AndroidRecyclerView/article.html
        binding.myRecyclerView.setHasFixedSize(true)

        if (savedInstanceState != null) {
            setData(savedInstanceState.getSerializable(KEY_DATA_SET) as? ArrayList<CSVRecord>)
            selectedRows = savedInstanceState.getParcelable(KEY_SELECTED_ROWS)!!
            firstLineIsHeader = savedInstanceState.getBoolean(KEY_FIRST_LINE_IS_HEADER)
        }
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun setData(data: List<CSVRecord>?) {
        if (data == null || data.isEmpty()) return
        dataSet = ArrayList(data)
        val nrOfColumns = dataSet[0].size()
        selectedRows = SparseBooleanArrayParcelable()
        for (i in 0 until dataSet.size) {
            selectedRows.put(i, true)
        }
        val availableCellWidth = ((windowWidth - checkboxColumnWidth - cellMargin * nrOfColumns * 2) / nrOfColumns)
        val cellWidth: Int
        val tableWidth: Int
        if (availableCellWidth > cellMinWidth) {
            cellWidth = availableCellWidth
            tableWidth = windowWidth
        } else {
            cellWidth = cellMinWidth
            tableWidth = cellMinWidth * nrOfColumns + checkboxColumnWidth + cellMargin * nrOfColumns * 2
        }
        cellParams = LinearLayout.LayoutParams(cellWidth, MATCH_PARENT).apply {
            setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
        }
        with(binding.myRecyclerView) {
            val params = layoutParams
            params.width = tableWidth
            layoutParams = params
            adapter = MyAdapter()
        }

        //set up header
        with(binding.headerLine) {
            removeViews(1, childCount - 1)
            for (i in 0 until nrOfColumns) {
                val cell = AppCompatSpinner(requireContext())
                cell.id = ViewCompat.generateViewId()
                cell.adapter = mFieldAdapter
                ViewCompat.setPaddingRelative(cell, 0, 0, 90, 0)
                addView(cell, cellParams)
            }
        }
    }

    fun setHeader() {
        firstLineIsHeader = true
        binding.myRecyclerView.adapter?.notifyItemChanged(0)
        //autoMap
        val record = dataSet[0]
        outer@ for (j in 0 until record.size()) {
            val headerLabel = Utils.normalize(record[j])
            val keys = header2FieldMap.keys()
            while (keys.hasNext()) {
                val storedLabel = keys.next()
                if (storedLabel == headerLabel) {
                    try {
                        val fieldKey = header2FieldMap.getString(storedLabel)
                        val position = listOf(*fieldKeys).indexOf(fieldKey)
                        if (position != -1) {
                            (binding.headerLine.getChildAt(j + 1) as Spinner).setSelection(position)
                            continue@outer
                        }
                    } catch (e: JSONException) {
                        //ignore
                    }
                }
            }
            for (i in 1 /* 0=Select ignored  */ until fields.size) {
                val fieldLabel = Utils.normalize(getString(fields[i]))
                if (fieldLabel == headerLabel) {
                    (binding.headerLine.getChildAt(j + 1) as Spinner).setSelection(i)
                    break
                }
            }
        }
    }

    private inner class MyAdapter : RecyclerView.Adapter<MyAdapter.ViewHolder>(), CompoundButton.OnCheckedChangeListener {
        override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
            val position = buttonView.tag as Int
            Timber.d("%s item at position %d", if (isChecked) "Selecting" else "Discarding", position)
            if (isChecked) {
                selectedRows.put(position, true)
                if (position == 0) {
                    firstLineIsHeader = false
                }
            } else {
                selectedRows.delete(position)
                if (position == 0) {
                    val b = Bundle()
                    b.putInt(ConfirmationDialogFragment.KEY_TITLE,
                            R.string.dialog_title_information)
                    b.putString(
                            ConfirmationDialogFragment.KEY_MESSAGE,
                            getString(R.string.cvs_import_set_first_line_as_header))
                    b.putInt(ConfirmationDialogFragment.KEY_COMMAND_POSITIVE,
                            R.id.SET_HEADER_COMMAND)
                    ConfirmationDialogFragment.newInstance(b).show(
                            parentFragmentManager, "SET_HEADER_CONFIRMATION")
                }
            }
            notifyItemChanged(position)
        }

        inner class ViewHolder(val itemBinding: ImportCsvDataRowBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup,
                                        viewType: Int): ViewHolder {
            val itemBinding = ImportCsvDataRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            for (i in 0 until nrOfColumns) {
                val cell = TextView(parent.context)
                cell.setSingleLine()
                cell.ellipsize = TextUtils.TruncateAt.END
                cell.isSelected = true
                cell.setOnClickListener { v1: View -> (requireActivity() as ProtectedFragmentActivity).showSnackbar((v1 as TextView).text) }
                if (viewType == 0) {
                    cell.setTypeface(null, Typeface.BOLD)
                }
                itemBinding.root.addView(cell, cellParams)
            }
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val isSelected = selectedRows[position, false]
            val isHeader = position == 0 && firstLineIsHeader
            holder.itemView.isActivated = !isSelected && !isHeader
            val record = dataSet[position]
            for (i in 0 until record.size().coerceAtLeast(nrOfColumns)) {
                val cell = holder.itemBinding.root.getChildAt(i + 1) as TextView
                cell.text = record[i]
            }
            with(holder.itemBinding.checkBox) {
                tag = position
                setOnCheckedChangeListener(null)
                isChecked = isSelected
                setOnCheckedChangeListener(this@MyAdapter)
            }
        }

        // Return the size of your dataSet (invoked by the layout manager)
        override fun getItemCount(): Int {
            return dataSet.size
        }

        override fun getItemViewType(position: Int): Int {
            return if (position == 0 && firstLineIsHeader) 0 else 1
        }

        // Provide a suitable constructor (depends on the kind of dataSet)
        init {
            nrOfColumns = dataSet[0].size()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_DATA_SET, dataSet)
        outState.putParcelable(KEY_SELECTED_ROWS, selectedRows)
        outState.putBoolean(KEY_FIRST_LINE_IS_HEADER, firstLineIsHeader)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.csv_import, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.IMPORT_COMMAND) {
            val columnToFieldMap = IntArray(nrOfColumns)
            val header = dataSet[0]
            for (i in 0 until nrOfColumns) {
                val position = (binding.headerLine.getChildAt(i + 1) as Spinner).selectedItemPosition
                columnToFieldMap[i] = fields[position]
                if (firstLineIsHeader) {
                    try {
                        if (fieldKeys[position] != FIELD_KEY_SELECT) {
                            header2FieldMap.put(Utils.normalize(header[i]), fieldKeys[position])
                        }
                    } catch (e: JSONException) {
                        CrashHandler.report(e)
                    }
                }
            }
            if (validateMapping(columnToFieldMap)) {
                prefHandler.putString(PrefKey.CSV_IMPORT_HEADER_TO_FIELD_MAP, header2FieldMap.toString())
                val selectedData = dataSet.filterIndexed { index, _ -> selectedRows[index] }
                (activity as? CsvImportActivity)?.importData(selectedData, columnToFieldMap, dataSet.size - selectedData.size)
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Check mapping constraints:<br></br>
     *
     *  * No field mapped more than once
     *  * Subcategory cannot be mapped without category
     *  * One of amount, income or expense must be mapped.
     *
     *
     * @param columnToFieldMap
     */
    private fun validateMapping(columnToFieldMap: IntArray): Boolean {
        val foundFields = SparseBooleanArray()
        val activity = requireActivity() as ProtectedFragmentActivity
        for (field in columnToFieldMap) {
            if (field != R.string.csv_import_discard) {
                if (foundFields[field, false]) {
                    activity.showSnackbar(getString(R.string.csv_import_field_mapped_more_than_once, getString(field)))
                    return false
                }
                foundFields.put(field, true)
            }
        }
        if (foundFields[R.string.subcategory, false] && !foundFields[R.string.category, false]) {
            activity.showSnackbar(R.string.csv_import_subcategory_requires_category)
            return false
        }
        if (!(foundFields[R.string.amount, false] ||
                        foundFields[R.string.expense, false] ||
                        foundFields[R.string.income, false])) {
            activity.showSnackbar(R.string.csv_import_no_mapping_found_for_amount)
            return false
        }
        return true
    }

    companion object {
        const val KEY_DATA_SET = "DATA_SET"
        const val KEY_SELECTED_ROWS = "SELECTED_ROWS"
        const val KEY_FIRST_LINE_IS_HEADER = "FIRST_LINE_IS_HEADER"
        const val FIELD_KEY_SELECT = "SELECT"
        const val FIELD_KEY_AMOUNT = "AMOUNT"
        const val FIELD_KEY_EXPENSE = "EXPENSE"
        const val FIELD_KEY_INCOME = "INCOME"
        const val FIELD_KEY_DATE = "DATE"
        const val FIELD_KEY_PAYEE = "PAYEE"
        const val FIELD_KEY_COMMENT = "COMMENT"
        const val FIELD_KEY_CATEGORY = "CATEGORY"
        const val FIELD_KEY_SUBCATEGORY = "SUB_CATEGORY"
        const val FIELD_KEY_METHOD = "METHOD"
        const val FIELD_KEY_STATUS = "STATUS"
        const val FIELD_KEY_NUMBER = "NUMBER"
        const val FIELD_KEY_SPLIT = "SPLIT"
        fun newInstance(): CsvImportDataFragment {
            return CsvImportDataFragment()
        }
    }
}